package com.example.gateway.resilience;

import java.util.function.LongSupplier;

/**
 * Shared, dynamic <b>retry budget</b> that caps the rate of fallback (retry) attempts across
 * requests, preventing a retry storm (M4-3). It is orthogonal to the per-request static
 * {@code maxAttempts} bound on {@link FallbackChain#dispatch}: {@code maxAttempts} caps the length
 * of a single chain, whereas this budget is a process-wide signal shared by all concurrent requests.
 *
 * <h2>Policy: token bucket</h2>
 * A bucket holds at most {@code capacity} tokens and refills continuously at {@code refillPerSecond}
 * tokens/second (computed from the injected clock — never the system clock). Each fallback consumes
 * exactly one token via {@link #tryRetry()}: if a token is available it is decremented and
 * {@code true} is returned (the fallback is allowed); if the bucket is empty {@code false} is
 * returned (the fallback is blocked, the chain stops). This lets a steady stream of failures burn
 * the burst {@code capacity}, after which fallbacks are admitted only at the slow refill rate —
 * exactly the retry-storm clamp we want.
 *
 * <p>The token-bucket policy is chosen over a cumulative retry-ratio policy because it is
 * deterministically unit-testable with an injected clock: refill-after-elapsed-time and
 * capacity-bounded burst are both directly assertable, and the budget needs no per-request
 * accounting handshake. The primary (first) attempt of a request never touches the budget — only
 * fallbacks call {@link #tryRetry()} — so a healthy primary path is never throttled.
 *
 * <h2>Clock</h2>
 * Time is supplied by an injected {@link LongSupplier} (epoch millis). Refill is lazily computed at
 * each {@link #tryRetry()} from elapsed millis, so the bucket is fully deterministic and a test can
 * advance a fake clock to refill it.
 *
 * <h2>Concurrency</h2>
 * The token count and the last-refill stamp are mutated under this instance's monitor, so the
 * refill-then-decrement is indivisible (the B/C store {@code synchronized} pattern). Under
 * concurrent {@link #tryRetry()} calls against an empty-refilling bucket, at most {@code capacity}
 * (plus whatever the elapsed clock has refilled) callers observe {@code true}.
 *
 * <p>Real Redis-shared budget state, real pipeline wiring, and persistence are out of scope for this
 * slice (follow-up); this is the in-memory component plus its {@link FallbackChain} integration.
 */
public final class RetryBudget {

    /** Deterministic {@code error_type}/flag stamped when a fallback is blocked for an empty budget. */
    public static final String EXHAUSTED = "retry_budget_exhausted";

    private final double capacity;
    private final double refillPerMilli;
    private final LongSupplier clock;

    private double tokens;
    private long lastRefillMillis;

    /**
     * @param capacity        maximum tokens (burst of fallbacks allowed back-to-back); &gt;= 1
     * @param refillPerSecond tokens replenished per second (the sustained fallback rate); &gt;= 0
     * @param clock           epoch-millis supplier (injected; never the system clock)
     */
    public RetryBudget(double capacity, double refillPerSecond, LongSupplier clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        if (refillPerSecond < 0) {
            throw new IllegalArgumentException("refillPerSecond must be >= 0");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.capacity = capacity;
        this.refillPerMilli = refillPerSecond / 1000.0;
        this.clock = clock;
        this.tokens = capacity; // start full
        this.lastRefillMillis = clock.getAsLong();
    }

    /**
     * Attempt to spend one token for a single fallback (retry).
     *
     * @return {@code true} if a token was available (decremented, fallback allowed); {@code false}
     *         if the budget is exhausted (no token spent, fallback must be blocked).
     */
    public synchronized boolean tryRetry() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Current available tokens (for assertions/observability). Refills lazily first. */
    public synchronized double available() {
        refill();
        return tokens;
    }

    /** Lazily add tokens accrued since the last refill, clamped to capacity. Caller holds the monitor. */
    private void refill() {
        long now = clock.getAsLong();
        long elapsed = now - lastRefillMillis;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerMilli);
            lastRefillMillis = now;
        }
    }
}
