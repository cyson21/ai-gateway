package com.example.gateway.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.gateway.provider.CompletionRequest;

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
        return route(alias, strategy, candidates, null);
    }

    public RoutingDecision route(
            String alias, RoutingStrategy strategy, List<ModelCandidate> candidates, CompletionRequest request) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias is required");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy is required");
        }
        List<ModelCandidate> ordered = order(strategy, candidates, request);
        if (ordered.isEmpty()) {
            throw new IllegalStateException("no healthy candidate for alias '" + alias + "'");
        }
        ModelCandidate chosen = ordered.get(0);
        String reason = switch (strategy) {
            case FIXED -> "fixed target";
            case LEAST_COST -> "lowest cost " + chosen.costPerKTokens() + " per 1K tokens";
            case LEAST_LATENCY -> "lowest latency " + chosen.latencyMs() + "ms";
            case WEIGHTED_SPLIT -> "weighted split bucket weight " + chosen.trafficWeight();
        };
        return new RoutingDecision(alias, strategy, chosen.provider(), chosen.model(), reason);
    }

    /**
     * Deterministic ordering used by both routing and fallback. The first element is
     * the primary choice; the rest are the ordered fallback chain.
     */
    public List<ModelCandidate> order(RoutingStrategy strategy, List<ModelCandidate> candidates) {
        return order(strategy, candidates, null);
    }

    public List<ModelCandidate> order(
            RoutingStrategy strategy, List<ModelCandidate> candidates, CompletionRequest request) {
        List<ModelCandidate> healthy = new ArrayList<>();
        if (candidates != null) {
            for (ModelCandidate c : candidates) {
                if (c.healthy()) {
                    healthy.add(c);
                }
            }
        }
        if (strategy == RoutingStrategy.WEIGHTED_SPLIT) {
            return weightedOrder(healthy, request);
        }
        Comparator<ModelCandidate> byTiebreak = Comparator.comparing(ModelCandidate::tiebreakKey);
        Comparator<ModelCandidate> comparator = switch (strategy) {
            case FIXED -> byTiebreak;
            case LEAST_COST -> Comparator.comparingLong(ModelCandidate::costPerKTokens).thenComparing(byTiebreak);
            case LEAST_LATENCY -> Comparator.comparingLong(ModelCandidate::latencyMs).thenComparing(byTiebreak);
            case WEIGHTED_SPLIT -> throw new IllegalStateException("weighted split handled separately");
        };
        healthy.sort(comparator);
        return healthy;
    }

    private List<ModelCandidate> weightedOrder(List<ModelCandidate> healthy, CompletionRequest request) {
        if (healthy.isEmpty()) {
            return List.of();
        }
        List<ModelCandidate> weighted = healthy.stream()
                .filter(candidate -> candidate.trafficWeight() > 0)
                .toList();
        if (weighted.isEmpty()) {
            return healthy;
        }
        int totalWeight = weighted.stream().mapToInt(ModelCandidate::trafficWeight).sum();
        int bucket = Math.floorMod(bucketKey(request).hashCode(), totalWeight);
        ModelCandidate primary = weighted.get(0);
        int cursor = 0;
        for (ModelCandidate candidate : weighted) {
            cursor += candidate.trafficWeight();
            if (bucket < cursor) {
                primary = candidate;
                break;
            }
        }
        List<ModelCandidate> ordered = new ArrayList<>();
        ordered.add(primary);
        for (ModelCandidate candidate : healthy) {
            if (!candidate.equals(primary)) {
                ordered.add(candidate);
            }
        }
        return List.copyOf(ordered);
    }

    private static String bucketKey(CompletionRequest request) {
        if (request == null || request.prompt() == null) {
            return "";
        }
        return request.prompt();
    }
}
