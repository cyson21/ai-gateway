package com.example.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * M4-3: the shared token-bucket retry budget. Time is driven by a fake epoch-millis clock
 * ({@link AtomicLong}) so refill is deterministic — no system clock is consulted.
 */
class RetryBudgetTest {

    // ---- burst capacity is bounded; empty bucket blocks ----------------------

    @Test
    void burstIsBoundedByCapacityThenBlocks() {
        AtomicLong clock = new AtomicLong(0);
        RetryBudget budget = new RetryBudget(3, 0, clock::get); // no refill

        assertThat(budget.tryRetry()).isTrue();  // 3 -> 2
        assertThat(budget.tryRetry()).isTrue();  // 2 -> 1
        assertThat(budget.tryRetry()).isTrue();  // 1 -> 0
        assertThat(budget.tryRetry()).isFalse(); // empty: blocked
        assertThat(budget.tryRetry()).isFalse(); // still blocked
    }

    // ---- time passing refills the bucket (injected clock) --------------------

    @Test
    void elapsedTimeRefillsBudget() {
        AtomicLong clock = new AtomicLong(0);
        RetryBudget budget = new RetryBudget(2, 1.0, clock::get); // 1 token/sec

        assertThat(budget.tryRetry()).isTrue();  // 2 -> 1
        assertThat(budget.tryRetry()).isTrue();  // 1 -> 0
        assertThat(budget.tryRetry()).isFalse(); // empty

        clock.addAndGet(1000); // +1s -> +1 token
        assertThat(budget.tryRetry()).isTrue();  // refilled to 1, spend -> 0
        assertThat(budget.tryRetry()).isFalse(); // empty again
    }

    @Test
    void refillIsClampedToCapacity() {
        AtomicLong clock = new AtomicLong(0);
        RetryBudget budget = new RetryBudget(2, 10.0, clock::get);

        assertThat(budget.tryRetry()).isTrue();
        assertThat(budget.tryRetry()).isTrue();
        assertThat(budget.available()).isZero();

        clock.addAndGet(10_000); // would accrue 100 tokens, but clamps to capacity 2
        assertThat(budget.available()).isEqualTo(2.0);
    }

    // ---- concurrency: exactly capacity callers see true ----------------------

    @Test
    void concurrentTryRetryAdmitsExactlyCapacity() throws InterruptedException {
        AtomicLong clock = new AtomicLong(0);
        int capacity = 25;
        RetryBudget budget = new RetryBudget(capacity, 0, clock::get); // frozen clock: no refill

        int threads = 16;
        int perThread = 50; // 800 attempts >> capacity 25
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (budget.tryRetry()) {
                            admitted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // Frozen clock means no refill: exactly the capacity is handed out, never more.
        assertThat(admitted.get()).isEqualTo(capacity);
        assertThat(budget.available()).isZero();
    }

    // ---- constructor validation ---------------------------------------------

    @Test
    void rejectsInvalidConstruction() {
        assertThatThrownBy(() -> new RetryBudget(0, 1.0, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryBudget(1, -1.0, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryBudget(1, 1.0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
