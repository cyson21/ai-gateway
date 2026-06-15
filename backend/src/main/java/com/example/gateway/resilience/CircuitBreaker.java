package com.example.gateway.resilience;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-provider circuit breaker as a first-class component (M4-2). Each provider id owns an
 * independent {@link CircuitState} cell; one provider tripping {@link CircuitState#OPEN} never
 * affects another. The breaker is the runtime <b>dynamic</b> health signal — distinct from the
 * static {@code healthy} flag on {@link com.example.gateway.router.ModelCandidate} that the
 * {@link com.example.gateway.router.PolicyRouter} consults at ordering time.
 *
 * <h2>State machine</h2>
 * <ul>
 *   <li><b>CLOSED</b>: {@link #allowRequest} returns {@code true}. {@link #recordFailure} increments
 *       a consecutive-failure counter; reaching {@code failureThreshold} trips the circuit to
 *       <b>OPEN</b> and stamps the open time. {@link #recordSuccess} resets the counter.</li>
 *   <li><b>OPEN</b>: {@link #allowRequest} returns {@code false} until {@code openMillis} have
 *       elapsed (per the injected clock) since the open stamp; at that point it transitions to
 *       <b>HALF_OPEN</b> and admits a single probe.</li>
 *   <li><b>HALF_OPEN</b>: the first {@link #allowRequest} after cooldown returns {@code true} once
 *       (the probe token); any further calls before the probe resolves return {@code false}.
 *       {@link #recordSuccess} on the probe closes the circuit and resets the failure count;
 *       {@link #recordFailure} re-opens it and restarts the cooldown.</li>
 * </ul>
 *
 * <h2>Clock</h2>
 * Time is supplied by an injected {@link LongSupplier} (epoch millis). No system clock is ever
 * consulted, so the state machine is fully deterministic and testable by advancing a fake clock.
 *
 * <h2>Concurrency</h2>
 * State lives in a {@link ConcurrentHashMap} of per-provider cells; every transition is performed
 * under the cell's monitor (the B/C store {@code synchronized} pattern), so failure counting and
 * the probe token are indivisible under concurrent {@code record*}/{@code allowRequest} calls.
 *
 * <p>Real Redis-shared breaker state, real pipeline wiring, and persistence are out of scope for
 * this slice (follow-up); this is the in-memory component plus its {@link FallbackChain} integration.
 */
public final class CircuitBreaker {

    /** Mutable per-provider state cell. All access is guarded by the cell's own monitor. */
    private static final class Cell {
        CircuitState state = CircuitState.CLOSED;
        int consecutiveFailures;
        long openedAtMillis;
        /** True while OPEN/HALF_OPEN has already handed out its single probe token. */
        boolean probeInFlight;
    }

    private final int failureThreshold;
    private final long openMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Cell> cells = new ConcurrentHashMap<>();

    /**
     * @param failureThreshold consecutive failures in CLOSED that trip the circuit OPEN (>= 1)
     * @param openMillis        cooldown the circuit stays OPEN before admitting a probe (>= 0)
     * @param clock             epoch-millis supplier (injected; never the system clock)
     */
    public CircuitBreaker(int failureThreshold, long openMillis, LongSupplier clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (openMillis < 0) {
            throw new IllegalArgumentException("openMillis must be >= 0");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
        this.clock = clock;
    }

    private Cell cell(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        return cells.computeIfAbsent(providerId, k -> new Cell());
    }

    /**
     * Whether a request to the provider should be attempted now.
     *
     * @return {@code true} in CLOSED, and once per cooldown in HALF_OPEN (the single probe token);
     *         {@code false} while OPEN (cooldown not elapsed) or while a HALF_OPEN probe is in flight.
     */
    public boolean allowRequest(String providerId) {
        Cell c = cell(providerId);
        synchronized (c) {
            maybeTripToHalfOpen(c);
            switch (c.state) {
                case CLOSED:
                    return true;
                case HALF_OPEN:
                    if (!c.probeInFlight) {
                        c.probeInFlight = true; // hand out the one probe token
                        return true;
                    }
                    return false;
                case OPEN:
                default:
                    return false;
            }
        }
    }

    /** Record a successful attempt: resets CLOSED failure count; closes a HALF_OPEN probe. */
    public void recordSuccess(String providerId) {
        Cell c = cell(providerId);
        synchronized (c) {
            maybeTripToHalfOpen(c);
            c.state = CircuitState.CLOSED;
            c.consecutiveFailures = 0;
            c.probeInFlight = false;
        }
    }

    /**
     * Record a failed attempt: in CLOSED, increments the consecutive-failure count and trips OPEN at
     * the threshold; in HALF_OPEN, re-opens the circuit and restarts the cooldown.
     */
    public void recordFailure(String providerId) {
        Cell c = cell(providerId);
        synchronized (c) {
            maybeTripToHalfOpen(c);
            if (c.state == CircuitState.HALF_OPEN) {
                open(c); // probe failed → re-open, restart cooldown
                return;
            }
            // CLOSED (OPEN can't be observed here: cooldown-elapsed OPEN became HALF_OPEN above,
            // and a not-elapsed OPEN would not have admitted a request to fail).
            c.consecutiveFailures++;
            if (c.consecutiveFailures >= failureThreshold) {
                open(c);
            }
        }
    }

    /** Current state (for assertions/observability). Reflects an elapsed-cooldown OPEN as HALF_OPEN. */
    public CircuitState state(String providerId) {
        Cell c = cell(providerId);
        synchronized (c) {
            maybeTripToHalfOpen(c);
            return c.state;
        }
    }

    /** Trip OPEN: stamp the open time, clear the probe token. Caller holds the cell monitor. */
    private void open(Cell c) {
        c.state = CircuitState.OPEN;
        c.openedAtMillis = clock.getAsLong();
        c.consecutiveFailures = failureThreshold; // keep the count at/above threshold while OPEN
        c.probeInFlight = false;
    }

    /** If OPEN and the cooldown has elapsed, move to HALF_OPEN (ready for one probe). */
    private void maybeTripToHalfOpen(Cell c) {
        if (c.state == CircuitState.OPEN && clock.getAsLong() - c.openedAtMillis >= openMillis) {
            c.state = CircuitState.HALF_OPEN;
            c.probeInFlight = false;
        }
    }
}
