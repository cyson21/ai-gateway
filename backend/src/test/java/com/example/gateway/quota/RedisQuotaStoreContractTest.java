package com.example.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

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
 * Integration tests proving the real Redis {@link RedisRateLimitStore} and {@link RedisBudgetStore}
 * satisfy the same atomic contracts the in-memory fakes do, on a live Redis spun up by
 * Testcontainers. The testcontainers BOM ships no Redis module, so the server is a plain
 * {@code redis:7-alpine} {@link GenericContainer} exposing 6379; a single shared Lettuce sync
 * connection drives both stores (the synchronous adapter the store contract requires). Each test
 * uses a fresh key (or FLUSHDB) so cases stay isolated. The multi-threaded distributed-load proof
 * is the E1b slice; here the basic boundary/isolation/clamp contracts are enough.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisQuotaStoreContractTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> commands;

    private RedisRateLimitStore rateStore;
    private RedisBudgetStore budgetStore;

    @BeforeAll
    static void assumeDockerAndConnect() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        client = RedisClient.create(
                RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection = client.connect();
        commands = connection.sync();
    }

    @AfterAll
    static void disconnect() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @BeforeEach
    void freshState() {
        commands.flushdb();
        rateStore = new RedisRateLimitStore(commands);
        budgetStore = new RedisBudgetStore(commands);
    }

    // ---- RedisRateLimitStore -------------------------------------------------

    @Test
    void rateLimitAdmitsUpToTheLimitThenRejectsWithoutRecording() {
        String key = "tenant-1:1m";
        long now = 1_000_000L;
        long window = 60_000L;

        assertThat(rateStore.tryAcquire(key, now, window, 3)).isTrue();
        assertThat(rateStore.tryAcquire(key, now + 1, window, 3)).isTrue();
        assertThat(rateStore.tryAcquire(key, now + 2, window, 3)).isTrue();

        // The 4th hit in the same window is rejected and not recorded.
        assertThat(rateStore.tryAcquire(key, now + 3, window, 3)).isFalse();
        assertThat(rateStore.tryAcquire(key, now + 4, window, 3)).isFalse();
    }

    @Test
    void rateLimitRecoversAfterTheWindowSlidesPast() {
        String key = "tenant-1:1m";
        long window = 60_000L;
        long now = 1_000_000L;

        assertThat(rateStore.tryAcquire(key, now, window, 1)).isTrue();
        assertThat(rateStore.tryAcquire(key, now + 1, window, 1)).isFalse();

        // Advance beyond the window: the earlier hit ages out, so a new one fits again.
        long later = now + window + 1;
        assertThat(rateStore.tryAcquire(key, later, window, 1)).isTrue();
    }

    @Test
    void rateLimitKeepsDifferentKeysIsolated() {
        long now = 1_000_000L;
        long window = 60_000L;

        assertThat(rateStore.tryAcquire("tenant-a:1m", now, window, 1)).isTrue();
        assertThat(rateStore.tryAcquire("tenant-a:1m", now + 1, window, 1)).isFalse();

        // A different tenant's window is independent.
        assertThat(rateStore.tryAcquire("tenant-b:1m", now + 1, window, 1)).isTrue();
    }

    // ---- RedisBudgetStore ----------------------------------------------------

    private static final long HUGE = Long.MAX_VALUE / 4;

    @Test
    void budgetReservesExactlyUpToTheTokenLimitThenRejects() {
        String key = "tenant-1:2026-06";

        assertThat(budgetStore.reserve(key, 60, 0, 100, HUGE)).isTrue();
        assertThat(budgetStore.reserve(key, 40, 0, 100, HUGE)).isTrue(); // exactly at the limit
        assertThat(budgetStore.consumedTokens(key)).isEqualTo(100);

        assertThat(budgetStore.reserve(key, 1, 0, 100, HUGE)).isFalse();
        assertThat(budgetStore.consumedTokens(key)).isEqualTo(100);
    }

    @Test
    void budgetRejectsWhenEitherLimitWouldBeExceededWithoutRecording() {
        String key = "tenant-1:2026-06";

        // Token fits but cost does not -> rejected, neither total moves (single atomic eval).
        assertThat(budgetStore.reserve(key, 10, 500, 1_000, 100)).isFalse();
        assertThat(budgetStore.consumedTokens(key)).isZero();
        assertThat(budgetStore.consumedCost(key)).isZero();

        // Cost fits but token does not -> still rejected, nothing recorded.
        assertThat(budgetStore.reserve(key, 5_000, 10, 1_000, 100)).isFalse();
        assertThat(budgetStore.consumedTokens(key)).isZero();
        assertThat(budgetStore.consumedCost(key)).isZero();

        // Both fit -> recorded.
        assertThat(budgetStore.reserve(key, 10, 50, 1_000, 100)).isTrue();
        assertThat(budgetStore.consumedTokens(key)).isEqualTo(10);
        assertThat(budgetStore.consumedCost(key)).isEqualTo(50);
    }

    @Test
    void budgetReleaseRefundsButNeverGoesNegative() {
        String key = "tenant-1:2026-06";
        budgetStore.reserve(key, 30, 30, 100, 100);

        budgetStore.release(key, 10, 10);
        assertThat(budgetStore.consumedTokens(key)).isEqualTo(20);
        assertThat(budgetStore.consumedCost(key)).isEqualTo(20);

        // Over-release is clamped at zero rather than going negative.
        budgetStore.release(key, 999, 999);
        assertThat(budgetStore.consumedTokens(key)).isZero();
        assertThat(budgetStore.consumedCost(key)).isZero();
    }

    @Test
    void budgetCommitReconcilesEstimateToActualUsage() {
        String key = "tenant-1:2026-06";

        // Settle to a smaller actual than reserved: the overage is refunded.
        budgetStore.reserve(key, 100, 100, 1_000, 1_000);
        budgetStore.commit(key, 100, 100, 30, 40);
        assertThat(budgetStore.consumedTokens(key)).isEqualTo(30);
        assertThat(budgetStore.consumedCost(key)).isEqualTo(40);

        // Settle to a larger actual than reserved: the shortfall is added.
        budgetStore.reserve(key, 50, 50, 1_000, 1_000); // tokens 80, cost 90
        budgetStore.commit(key, 50, 50, 70, 60);
        assertThat(budgetStore.consumedTokens(key)).isEqualTo(100); // 80 - 50 + 70
        assertThat(budgetStore.consumedCost(key)).isEqualTo(100);   // 90 - 50 + 60
    }

    @Test
    void budgetKeepsDifferentKeysIsolated() {
        assertThat(budgetStore.reserve("tenant-a:2026-06", 100, 100, 100, 100)).isTrue();
        assertThat(budgetStore.reserve("tenant-a:2026-06", 1, 0, 100, 100)).isFalse();

        // A different tenant's budget is independent.
        assertThat(budgetStore.reserve("tenant-b:2026-06", 100, 100, 100, 100)).isTrue();
        assertThat(budgetStore.consumedTokens("tenant-b:2026-06")).isEqualTo(100);
    }

    @Test
    void budgetReserveFailureLeavesBothConsumedTotalsUnchanged() {
        String key = "tenant-1:2026-06";
        budgetStore.reserve(key, 40, 40, 100, 100);

        // This draw would cross the cost limit. The single Lua eval must record nothing — both
        // consumed totals stay exactly where they were (no partial token write).
        assertThat(budgetStore.reserve(key, 10, 70, 100, 100)).isFalse();
        assertThat(budgetStore.consumedTokens(key)).isEqualTo(40);
        assertThat(budgetStore.consumedCost(key)).isEqualTo(40);
    }
}
