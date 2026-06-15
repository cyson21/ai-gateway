package com.example.gateway.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic policy router. Given an alias, a strategy, and the declared
 * candidates, it always returns the same choice and an explanation. Unhealthy
 * candidates are excluded. Ties are broken by {@link ModelCandidate#tiebreakKey()}.
 */
public final class PolicyRouter {

    /**
     * @return the chosen candidate ordered list head, never null
     * @throws IllegalStateException if no healthy candidate exists
     */
    public RoutingDecision route(String alias, RoutingStrategy strategy, List<ModelCandidate> candidates) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias is required");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy is required");
        }
        List<ModelCandidate> ordered = order(strategy, candidates);
        if (ordered.isEmpty()) {
            throw new IllegalStateException("no healthy candidate for alias '" + alias + "'");
        }
        ModelCandidate chosen = ordered.get(0);
        String reason = switch (strategy) {
            case FIXED -> "fixed target";
            case LEAST_COST -> "lowest cost " + chosen.costPerKTokens() + " per 1K tokens";
            case LEAST_LATENCY -> "lowest latency " + chosen.latencyMs() + "ms";
        };
        return new RoutingDecision(alias, strategy, chosen.provider(), chosen.model(), reason);
    }

    /**
     * Deterministic ordering used by both routing and fallback. The first element is
     * the primary choice; the rest are the ordered fallback chain.
     */
    public List<ModelCandidate> order(RoutingStrategy strategy, List<ModelCandidate> candidates) {
        List<ModelCandidate> healthy = new ArrayList<>();
        if (candidates != null) {
            for (ModelCandidate c : candidates) {
                if (c.healthy()) {
                    healthy.add(c);
                }
            }
        }
        Comparator<ModelCandidate> byTiebreak = Comparator.comparing(ModelCandidate::tiebreakKey);
        Comparator<ModelCandidate> comparator = switch (strategy) {
            case FIXED -> byTiebreak;
            case LEAST_COST -> Comparator.comparingLong(ModelCandidate::costPerKTokens).thenComparing(byTiebreak);
            case LEAST_LATENCY -> Comparator.comparingLong(ModelCandidate::latencyMs).thenComparing(byTiebreak);
        };
        healthy.sort(comparator);
        return healthy;
    }
}
