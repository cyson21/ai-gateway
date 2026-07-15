package com.example.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Multi-threaded distributed-load proof for the real-Redis quota stores (E1b). Where the E1a
 * {@link RedisQuotaStoreContractTest} pins down the single-threaded boundary/isolation/clamp
 * contracts, this test drives concurrent load through Redis and asserts the admitted-count
 * reconciles exactly with both the client-side tally <em>and</em> the actual Redis state
 * ({@code ZCARD rl:key} for rate, {@code HGET bg:key} consumed for budget).
 *
 * <p><strong>Multi-instance approximation.</strong> Each worker thread opens its own independent
 * Lettuce connection ({@code client.connect().sync()}) and wraps it in its own store instance, so
 * every worker hits the shared Redis over a distinct client connection rather than serialising
 * through one shared {@link RedisCommands}. That makes the proof about Redis being the single source
 * of atomicity (the Lua {@code EVAL}), not about a single JVM client serialising the calls — the
 * same property a horizontally fanned-out set of gateway instances would rely on. Per-worker
 * connections are closed when the run finishes.
 *
 * <p><strong>Clock handling.</strong> Rate-limit cases inject a fixed {@code windowMillis} large
 * enough that the entire concurrent burst lands inside one window, and a constant (or tightly
 * bounded) {@code nowMillis}, so the system clock's jitter never ages a hit out mid-burst and the
 * count is fully deterministic.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisQuotaStoreConcurrencyTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> adminConnection;
    private static RedisCommands<String, String> admin;

    @BeforeAll
    static void assumeDockerAndConnect() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        client = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        adminConnection = client.connect();
        admin = adminConnection.sync();
    }

    @AfterAll
    static void disconnect() {
        if (adminConnection != null) {
            adminConnection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @BeforeEach
    void freshState() {
        admin.flushdb();
    }

    /**
     * Runs {@code taskCount} tasks across {@code threads} worker threads, each worker holding its own
     * independent Lettuce connection and store built from {@code storeFactory}. All tasks block on a
     * single latch and fire together. Returns nothing; per-task work is supplied by the caller.
     */
    private <S> void runConcurrent(
            int threads,
            int taskCount,
            java.util.function.Function<RedisCommands<String, String>, S> storeFactory,
            java.util.function.BiConsumer<S, Integer> task)
            throws InterruptedException {

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        // One independent connection per worker thread (multi-instance fan-out approximation).
        List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
        ThreadLocal<S> threadStore = ThreadLocal.withInitial(() -> {
            StatefulRedisConnection<String, String> conn = client.connect();
            synchronized (connections) {
                connections.add(conn);
            }
            return storeFactory.apply(conn.sync());
        });

        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                final int index = i;
                jobs.add(() -> {
                    S store = threadStore.get();
                    start.await();
                    task.accept(store, index);
                    return null;
                });
            }
            for (Callable<Void> job : jobs) {
                pool.submit(job);
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            for (StatefulRedisConnection<String, String> conn : connections) {
                conn.close();
            }
        }
    }

    // ---- RateLimit concurrency ----------------------------------------------

    @Test
    void concurrentRateLimitAdmitsExactlyTheLimitAndRedisAgrees() throws InterruptedException {
        String key = "tenant-1:1m";
        long now = 1_000_000L;        // fixed clock: whole burst in one window
        long window = 60_000L;        // large enough that nothing ages out mid-burst
        int limit = 100;
        int threads = 8;
        int requests = 300;           // 3x the limit, all concurrent

        AtomicLong admitted = new AtomicLong();
        runConcurrent(
                threads,
                requests,
                RedisRateLimitStore::new,
                (store, i) -> {
                    if (store.tryAcquire(key, now, window, limit)) {
                        admitted.incrementAndGet();
                    }
                });

        // Client tally == limit, and Redis's own surviving-hit count == limit. Three-way agreement.
        assertThat(admitted.get()).isEqualTo(limit);
        assertThat(admin.zcard("rl:" + key)).isEqualTo((long) limit);
    }

    // ---- Budget concurrency --------------------------------------------------

    private static final long HUGE = Long.MAX_VALUE / 4;

    @Test
    void concurrentTokenReservesAdmitExactlyTheTokenLimitAndRedisAgrees() throws InterruptedException {
        String key = "tenant-1:2026-06";
        int tokenLimit = 100;
        int threads = 8;
        int reserves = 250;          // each reserves 1 token; only 100 can fit

        AtomicLong admitted = new AtomicLong();
        runConcurrent(
                threads,
                reserves,
                RedisBudgetStore::new,
                (store, i) -> {
                    if (store.reserve(key, 1, 0, tokenLimit, HUGE)) {
                        admitted.incrementAndGet();
                    }
                });

        assertThat(admitted.get()).isEqualTo(tokenLimit);
        assertThat(new RedisBudgetStore(admin).consumedTokens(key)).isEqualTo(tokenLimit);
    }

    @Test
    void concurrentReservesBindOnTheTighterOfTokenAndCostLimits() throws InterruptedException {
        String key = "tenant-1:2026-06";
        // Each reserve draws 1 token and 2 cost. Tokens would allow 1000, but cost caps it at 200/2 = 100.
        int tokenLimit = 1_000;
        int costLimit = 200;
        int threads = 8;
        int reserves = 300;

        AtomicLong admitted = new AtomicLong();
        runConcurrent(
                threads,
                reserves,
                RedisBudgetStore::new,
                (store, i) -> {
                    if (store.reserve(key, 1, 2, tokenLimit, costLimit)) {
                        admitted.incrementAndGet();
                    }
                });

        // The cost dimension binds: exactly 100 reserves fit (100*2 == 200 cost).
        assertThat(admitted.get()).isEqualTo(100L);
        RedisBudgetStore view = new RedisBudgetStore(admin);
        assertThat(view.consumedTokens(key)).isEqualTo(100L);
        assertThat(view.consumedCost(key)).isEqualTo(200L);
    }

    @Test
    void concurrentReserveReleaseMixHoldsNonNegativeInvariantAndSettlesToZero()
            throws InterruptedException {
        String key = "tenant-1:2026-06";
        int tokenLimit = 200;
        int threads = 16;
        int iterations = 200;        // per task: reserve 1 then release 1, asserting >= 0 throughout

        runConcurrent(
                threads,
                threads,             // one long-running task per thread
                RedisBudgetStore::new,
                (store, i) -> {
                    for (int n = 0; n < iterations; n++) {
                        store.reserve(key, 1, 1, tokenLimit, HUGE);
                        store.release(key, 1, 1);
                        assertThat(store.consumedTokens(key)).isGreaterThanOrEqualTo(0L);
                        assertThat(store.consumedCost(key)).isGreaterThanOrEqualTo(0L);
                    }
                });

        // Balanced reserve/release across all threads: Redis settles back to exactly zero, never negative.
        RedisBudgetStore view = new RedisBudgetStore(admin);
        assertThat(view.consumedTokens(key)).isZero();
        assertThat(view.consumedCost(key)).isZero();
    }
}
