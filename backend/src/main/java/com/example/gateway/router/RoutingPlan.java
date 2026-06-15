package com.example.gateway.router;

import java.util.List;

/**
 * The strategy and declared candidates for a model alias, resolved per tenant.
 */
public record RoutingPlan(RoutingStrategy strategy, List<ModelCandidate> candidates) {

    public RoutingPlan {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy is required");
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
