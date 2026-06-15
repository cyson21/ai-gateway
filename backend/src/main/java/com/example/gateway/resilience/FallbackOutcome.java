package com.example.gateway.resilience;

/**
 * Terminal outcome of a single fallback-chain attempt, projected 1:1 onto the
 * {@code fallback_events.outcome} TEXT column ({@code SUCCESS} / {@code FAILED}).
 */
public enum FallbackOutcome {
    SUCCESS,
    FAILED
}
