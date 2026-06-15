package com.example.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the atomic reservation contract of {@link InMemoryBudgetStore} — the single-
 * instance stand-in for the Redis-backed budget counter. The focus is the {@code consumed >= 0}
 * invariant under concurrent reserve/release/commit, which mirrors the {@code budgets_consumed_nonneg}
 * CHECK; real-Redis and multi-instance proof are the E1 slice.
 */
class InMemoryBudgetStoreTest {

    private static final String KEY = "budget:tenant-1:2026-06";
    private static final long HUGE = Long.MAX_VALUE / 4; // non-binding limit for the other dimension

    @Test
    void reservesExactlyUpToTheTokenLimitThenRejects() {
        InMemoryBudgetStore store = new InMemoryBudgetStore();

        assertThat(store.reserve(KEY, 60, 0, 100, HUGE)).isTrue();
        assertThat(store.reserve(KEY, 40, 0, 100, HUGE)).isTrue(); // exactly at the limit of 100
        assertThat(store.consumedTokens(KEY)).isEqualTo(100);

        // One more token would cross the limit: rejected, and nothing is recorded.
        assertThat(store.reserve(KEY, 1, 0, 100, HUGE)).isFalse();
        assertThat(store.consumedTokens(KEY)).isEqualTo(100);
    }

    @Test
    void rejectsWhenEitherTokenOrCostLimitWouldBeExceeded() {
        InMemoryBudgetStore store = new InMemoryBudgetStore();

        // Token fits but cost does not -> rejected, neither total moves.
        assertThat(store.reserve(KEY, 10, 500, 1_000, 100)).isFalse();
        assertThat(store.consumedTokens(KEY)).isZero();
        assertThat(store.consumedCost(KEY)).isZero();

        // Both fit -> recorded.
        assertThat(store.reserve(KEY, 10, 50, 1_000, 100)).isTrue();
        assertThat(store.consumedCost(KEY)).isEqualTo(50);
    }

    @Test
    void releaseRefundsButNeverGoesNegative() {
        InMemoryBudgetStore store = new InMemoryBudgetStore();
        store.reserve(KEY, 30, 30, 100, 100);

        store.release(KEY, 10, 10);
        assertThat(store.consumedTokens(KEY)).isEqualTo(20);
        assertThat(store.consumedCost(KEY)).isEqualTo(20);

        // Over-release is clamped at zero rather than going negative.
        store.release(KEY, 999, 999);
        assertThat(store.consumedTokens(KEY)).isZero();
        assertThat(store.consumedCost(KEY)).isZero();
    }

    @Test
    void commitReconcilesEstimateToActualUsage() {
        InMemoryBudgetStore store = new InMemoryBudgetStore();

        // Reserve an estimate, then settle to a smaller actual: the overage is refunded.
        store.reserve(KEY, 100, 100, 1_000, 1_000);
        store.commit(KEY, 100, 100, 30, 40);
        assertThat(store.consumedTokens(KEY)).isEqualTo(30);
        assertThat(store.consumedCost(KEY)).isEqualTo(40);

        // Settle to a larger actual than reserved: the shortfall is added.
        store.reserve(KEY, 50, 50, 1_000, 1_000); // tokens 80, cost 90
        store.commit(KEY, 50, 50, 70, 60);
        assertThat(store.consumedTokens(KEY)).isEqualTo(100); // 80 - 50 + 70
        assertThat(store.consumedCost(KEY)).isEqualTo(100);   // 90 - 50 + 60
    }

    @Test
    void concurrentReservesAdmitExactlyTheLimit() throws InterruptedException {
        InMemoryBudgetStore store = new InMemoryBudgetStore();
        int tokenLimit = 100;
        int tasks = 150; // each reserves 1 token; only 100 can fit

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong admitted = new AtomicLong();
        try {
            for (int i = 0; i < tasks; i++) {
                pool.submit((Callable<Void>) () -> {
                    start.await();
                    if (store.reserve(KEY, 1, 1, tokenLimit, HUGE)) {
                        admitted.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(admitted.get()).isEqualTo(tokenLimit);
        assertThat(store.consumedTokens(KEY)).isEqualTo(tokenLimit);
    }

    @Test
    void concurrentReserveReleaseMixNeverBreaksTheNonNegativeInvariant() throws InterruptedException {
        InMemoryBudgetStore store = new InMemoryBudgetStore();
        int tokenLimit = 200;
        int threads = 16;
        int iterations = 500;

        // Each thread repeatedly reserves one unit then immediately releases it. Reserves may be
        // refused (limit contention) but releases always run, so a non-atomic or unclamped store
        // would drift negative. The net effect must leave consumed at zero and never below it.
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit((Callable<Void>) () -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        store.reserve(KEY, 1, 1, tokenLimit, HUGE);
                        store.release(KEY, 1, 1);
                        assertThat(store.consumedTokens(KEY)).isGreaterThanOrEqualTo(0);
                        assertThat(store.consumedCost(KEY)).isGreaterThanOrEqualTo(0);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.consumedTokens(KEY)).isZero();
        assertThat(store.consumedCost(KEY)).isZero();
    }
}
