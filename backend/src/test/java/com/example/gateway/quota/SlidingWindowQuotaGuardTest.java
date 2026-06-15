package com.example.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.example.gateway.provider.CompletionRequest;

/**
 * Unit tests for {@link SlidingWindowQuotaGuard} driven by the in-memory {@link RateLimitStore},
 * so the rate limit decision is exercised without a live Redis. Real-Redis and multi-instance
 * concurrency are the E1 slice; here the atomic store contract is enough to prove the boundary.
 */
class SlidingWindowQuotaGuardTest {

    private final Map<String, RateLimitPolicy> policies = new HashMap<>();
    private final RateLimitPolicyProvider policyProvider =
            tenantId -> Optional.ofNullable(policies.get(tenantId));

    private CompletionRequest request(String tenantId) {
        return new CompletionRequest(tenantId, "gpt-4o", "hello", 16, false);
    }

    @Test
    void allowsExactlyUpToTheLimitThenRejects() {
        policies.put("tenant-1", new RateLimitPolicy(60, 3));
        SlidingWindowQuotaGuard guard = new SlidingWindowQuotaGuard(
                new InMemoryRateLimitStore(), policyProvider, fixedClock(1_000_000L));

        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        // Limit of 3 is exactly reached above; the 4th hit in the same window is rejected.
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.RATE_LIMITED);
    }

    @Test
    void tenantWithoutPolicyIsNeverRateLimited() {
        SlidingWindowQuotaGuard guard = new SlidingWindowQuotaGuard(
                new InMemoryRateLimitStore(), policyProvider, fixedClock(1_000_000L));

        for (int i = 0; i < 100; i++) {
            assertThat(guard.evaluate(request("no-policy"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        }
    }

    @Test
    void limitsAreIsolatedPerTenant() {
        policies.put("tenant-a", new RateLimitPolicy(60, 1));
        policies.put("tenant-b", new RateLimitPolicy(60, 1));
        SlidingWindowQuotaGuard guard = new SlidingWindowQuotaGuard(
                new InMemoryRateLimitStore(), policyProvider, fixedClock(1_000_000L));

        assertThat(guard.evaluate(request("tenant-a"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        assertThat(guard.evaluate(request("tenant-a"), 10)).isEqualTo(QuotaOutcome.RATE_LIMITED);
        // tenant-b has its own window and is unaffected by tenant-a's exhaustion.
        assertThat(guard.evaluate(request("tenant-b"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
    }

    @Test
    void windowSlidesSoSlotsRecoverAfterItPasses() {
        policies.put("tenant-1", new RateLimitPolicy(60, 2));
        AtomicLong nowMillis = new AtomicLong(1_000_000L);
        SlidingWindowQuotaGuard guard = new SlidingWindowQuotaGuard(
                new InMemoryRateLimitStore(), policyProvider, movableClock(nowMillis));

        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.RATE_LIMITED);

        // Advance just past the 60s window: the two earlier hits age out and slots free up.
        nowMillis.addAndGet(60_001L);
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.RATE_LIMITED);
    }

    @Test
    void concurrentRequestsRejectOnlyTheOverflow() throws InterruptedException {
        int maxRequests = 50;
        int totalRequests = 120;
        policies.put("tenant-1", new RateLimitPolicy(60, maxRequests));
        SlidingWindowQuotaGuard guard = new SlidingWindowQuotaGuard(
                new InMemoryRateLimitStore(), policyProvider, fixedClock(1_000_000L));

        // All tasks are queued first, then released together so many threads hit the atomic
        // tryAcquire near-simultaneously. The pool is smaller than the task count, so the start
        // latch (not a per-task barrier) is what makes them contend without deadlocking.
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<QuotaOutcome> outcomes = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < totalRequests; i++) {
                pool.submit((Callable<Void>) () -> {
                    start.await();
                    outcomes.add(guard.evaluate(request("tenant-1"), 10));
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long allowed = outcomes.stream().filter(o -> o == QuotaOutcome.ALLOWED).count();
        long rejected = outcomes.stream().filter(o -> o == QuotaOutcome.RATE_LIMITED).count();
        assertThat(outcomes).hasSize(totalRequests);
        assertThat(allowed).isEqualTo(maxRequests);
        assertThat(rejected).isEqualTo(totalRequests - maxRequests);
    }

    private static Clock fixedClock(long epochMillis) {
        return Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private static Clock movableClock(AtomicLong epochMillis) {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(epochMillis.get());
            }

            @Override
            public long millis() {
                return epochMillis.get();
            }
        };
    }
}
