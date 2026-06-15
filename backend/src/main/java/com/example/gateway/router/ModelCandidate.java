package com.example.gateway.router;

/**
 * A concrete provider/model target declared for a model alias, with the cost and
 * latency the router uses for policy decisions.
 *
 * @param provider        provider id, e.g. {@code "fake"}, {@code "openai"}
 * @param model           concrete model id at the provider
 * @param costPerKTokens  declared cost per 1K tokens, in micro-units (deterministic, not live)
 * @param latencyMs       declared typical latency in milliseconds
 * @param healthy         whether the candidate is currently selectable
 */
public record ModelCandidate(String provider, String model, long costPerKTokens, long latencyMs, boolean healthy) {

    public ModelCandidate {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (costPerKTokens < 0 || latencyMs < 0) {
            throw new IllegalArgumentException("cost and latency must be non-negative");
        }
    }

    /** Deterministic tiebreaker key: provider then model. */
    public String tiebreakKey() {
        return provider + "/" + model;
    }
}
