package com.example.gateway.resilience;

/**
 * Projection of one fallback-chain attempt onto the {@code fallback_events} columns
 * (V1 schema: attempt_no, provider, model, error_type, outcome). The enum
 * {@code outcome} maps to its {@code name()} TEXT at persistence time; {@code errorType}
 * is null on a {@link FallbackOutcome#SUCCESS} attempt and a deterministic label on a
 * {@link FallbackOutcome#FAILED} attempt (exception {@code simpleName} or its message).
 *
 * <p>{@code attemptNo} is 0-based and consistent with the chain ordering produced by
 * {@link com.example.gateway.router.PolicyRouter#order}: index 0 is the primary choice,
 * later indices are the ordered fallback chain.
 *
 * <p>The {@code request_id} FK is not modeled here: this slice produces the per-request
 * event list only; the FK wiring to {@code request_logs} is a later persistence slice.
 */
public record FallbackEvent(
        int attemptNo,
        String provider,
        String model,
        String errorType,
        FallbackOutcome outcome) {

    public FallbackEvent {
        if (attemptNo < 0) {
            throw new IllegalArgumentException("attemptNo must be non-negative");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        if (outcome == FallbackOutcome.SUCCESS && errorType != null) {
            throw new IllegalArgumentException("a SUCCESS attempt must not carry an errorType");
        }
    }

    /** A successful attempt: no error_type. */
    public static FallbackEvent success(int attemptNo, String provider, String model) {
        return new FallbackEvent(attemptNo, provider, model, null, FallbackOutcome.SUCCESS);
    }

    /** A failed attempt with a deterministic error label. */
    public static FallbackEvent failed(int attemptNo, String provider, String model, String errorType) {
        return new FallbackEvent(attemptNo, provider, model, errorType, FallbackOutcome.FAILED);
    }
}
