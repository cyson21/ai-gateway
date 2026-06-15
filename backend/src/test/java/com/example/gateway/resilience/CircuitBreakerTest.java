package com.example.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * M4-2: the per-provider circuit breaker state machine. Time is driven by a fake epoch-millis
 * clock ({@link AtomicLong}) so every CLOSED/OPEN/HALF_OPEN transition is deterministic — no system
 * clock is consulted.
 */
class CircuitBreakerTest {

    /** Threshold=3, cooldown=1000ms, fake clock starting at t=0. */
    private static CircuitBreaker breaker(AtomicLong clock) {
        return new CircuitBreaker(3, 1000L, clock::get);
    }

    // ---- threshold trips OPEN; cooldown elapses to HALF_OPEN probe ----------

    @Test
    void consecutiveFailuresTripOpenThenCooldownAdmitsProbe() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker cb = breaker(clock);

        cb.recordFailure("p-a");
        cb.recordFailure("p-a");
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.CLOSED); // 2 < threshold 3
        assertThat(cb.allowRequest("p-a")).isTrue();

        cb.recordFailure("p-a"); // 3rd consecutive → OPEN
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.OPEN);
        assertThat(cb.allowRequest("p-a")).isFalse(); // blocked while OPEN

        clock.set(999); // cooldown not yet elapsed
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.OPEN);
        assertThat(cb.allowRequest("p-a")).isFalse();

        clock.set(1000); // cooldown elapsed → HALF_OPEN
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(cb.allowRequest("p-a")).isTrue();  // single probe token
        assertThat(cb.allowRequest("p-a")).isFalse(); // probe already in flight
    }

    // ---- HALF_OPEN probe success closes; resets failure count ---------------

    @Test
    void halfOpenProbeSuccessClosesAndResets() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker cb = breaker(clock);

        cb.recordFailure("p-a");
        cb.recordFailure("p-a");
        cb.recordFailure("p-a"); // OPEN
        clock.set(1000);
        assertThat(cb.allowRequest("p-a")).isTrue(); // probe admitted

        cb.recordSuccess("p-a"); // probe succeeded
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.CLOSED);
        assertThat(cb.allowRequest("p-a")).isTrue();

        // failure count was reset: it again takes 3 consecutive failures to re-open.
        cb.recordFailure("p-a");
        cb.recordFailure("p-a");
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.CLOSED);
        cb.recordFailure("p-a");
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.OPEN);
    }

    // ---- HALF_OPEN probe failure re-opens; cooldown restarts ----------------

    @Test
    void halfOpenProbeFailureReopensAndRestartsCooldown() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker cb = breaker(clock);

        cb.recordFailure("p-a");
        cb.recordFailure("p-a");
        cb.recordFailure("p-a"); // OPEN at t=0
        clock.set(1000);
        assertThat(cb.allowRequest("p-a")).isTrue(); // probe at t=1000

        cb.recordFailure("p-a"); // probe failed → OPEN, cooldown restarts from t=1000
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.OPEN);

        clock.set(1999); // 999ms into the new cooldown
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.OPEN);
        clock.set(2000); // new cooldown elapsed
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(cb.allowRequest("p-a")).isTrue();
    }

    // ---- success between sub-threshold failures resets the counter ----------

    @Test
    void successResetsConsecutiveFailureCount() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker cb = breaker(clock);

        cb.recordFailure("p-a");
        cb.recordFailure("p-a"); // 2 failures
        cb.recordSuccess("p-a"); // resets to 0
        cb.recordFailure("p-a");
        cb.recordFailure("p-a"); // only 2 again, not 4
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.CLOSED);
        assertThat(cb.allowRequest("p-a")).isTrue();
    }

    // ---- per-provider isolation --------------------------------------------

    @Test
    void providersAreIsolated() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker cb = breaker(clock);

        cb.recordFailure("p-a");
        cb.recordFailure("p-a");
        cb.recordFailure("p-a"); // p-a OPEN

        assertThat(cb.state("p-a")).isEqualTo(CircuitState.OPEN);
        assertThat(cb.allowRequest("p-a")).isFalse();

        // p-b is untouched.
        assertThat(cb.state("p-b")).isEqualTo(CircuitState.CLOSED);
        assertThat(cb.allowRequest("p-b")).isTrue();
    }

    // ---- light concurrency: concurrent failures trip exactly once ----------

    @Test
    void concurrentFailuresTransitionConsistently() throws InterruptedException {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker cb = new CircuitBreaker(50, 1000L, clock::get);

        int threads = 16;
        int perThread = 100; // 1600 failures >> threshold 50
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        cb.recordFailure("p-a");
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

        // Regardless of interleaving, the terminal state is a consistent OPEN (blocked).
        assertThat(cb.state("p-a")).isEqualTo(CircuitState.OPEN);
        assertThat(cb.allowRequest("p-a")).isFalse();
    }

    // ---- constructor validation --------------------------------------------

    @Test
    void rejectsInvalidConstruction() {
        assertThatThrownBy(() -> new CircuitBreaker(0, 1000L, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker(1, -1L, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker(1, 1000L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
