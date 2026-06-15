package com.example.gateway.resilience;

/**
 * The three states of a provider's circuit breaker.
 *
 * <ul>
 *   <li>{@link #CLOSED}: normal operation — requests pass through; consecutive failures are counted
 *       and, once they reach the threshold, the circuit trips {@link #OPEN}.</li>
 *   <li>{@link #OPEN}: tripped — requests are blocked. After the cooldown elapses the circuit moves
 *       to {@link #HALF_OPEN} to allow a single probe.</li>
 *   <li>{@link #HALF_OPEN}: probing — a single probe request is admitted. A successful probe closes
 *       the circuit (and resets the failure count); a failed probe re-opens it (restarting cooldown).</li>
 * </ul>
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
