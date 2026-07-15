package com.example.gateway.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class PolicyRouterTest {

    private static ModelCandidate candidate(
            String provider, String model, long costPerKTokens, long latencyMs, boolean healthy) {
        return new ModelCandidate(provider, model, costPerKTokens, latencyMs, healthy);
    }

    @Test
    void fixedStrategyUsesDeterministicTiebreakerForChoiceAndFallback() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(
                candidate("zeta", "zebra", 5L, 40L, true),
                candidate("alpha", "zulu", 2L, 20L, true),
                candidate("alpha", "aardvark", 3L, 60L, true),
                candidate("alpha", "zebra", 8L, 90L, false));

        RoutingDecision decision = router.route("chat", RoutingStrategy.FIXED, candidates);

        assertThat(decision.provider()).isEqualTo("alpha");
        assertThat(decision.model()).isEqualTo("aardvark");
        assertThat(decision.reason()).isEqualTo("fixed target");
        assertThat(router.order(RoutingStrategy.FIXED, candidates))
                .extracting(ModelCandidate::tiebreakKey)
                .containsExactly("alpha/aardvark", "alpha/zulu", "zeta/zebra");
    }

    @Test
    void leastCostStrategyReturnsCheapestCandidateAndExplainsReason() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(
                candidate("beta", "higher", 9L, 30L, true),
                candidate("gamma", "cheapest", 2L, 80L, true),
                candidate("alpha", "cheap", 2L, 50L, true));

        RoutingDecision decision = router.route("chat", RoutingStrategy.LEAST_COST, candidates);

        assertThat(decision.provider()).isEqualTo("alpha");
        assertThat(decision.model()).isEqualTo("cheap");
        assertThat(decision.reason()).isEqualTo("lowest cost 2 per 1K tokens");
    }

    @Test
    void leastLatencyStrategyReturnsLowestLatencyCandidateAndExplainsReason() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(
                candidate("beta", "normal", 10L, 90L, true),
                candidate("gamma", "best", 5L, 20L, true),
                candidate("alpha", "also-best", 8L, 20L, true));

        RoutingDecision decision = router.route("chat", RoutingStrategy.LEAST_LATENCY, candidates);

        assertThat(decision.provider()).isEqualTo("alpha");
        assertThat(decision.model()).isEqualTo("also-best");
        assertThat(decision.reason()).isEqualTo("lowest latency 20ms");
    }

    @Test
    void unhealthyCandidatesAreExcludedFromOrderingAndSelection() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(
                candidate("unhealthy", "banned", 0L, 1L, false),
                candidate("healthy", "fallback", 3L, 10L, true),
                candidate("healthy", "primary", 1L, 5L, true));

        RoutingDecision decision = router.route("chat", RoutingStrategy.LEAST_COST, candidates);

        assertThat(decision.provider()).isEqualTo("healthy");
        assertThat(decision.model()).isEqualTo("primary");
        assertThat(router.order(RoutingStrategy.FIXED, candidates))
                .extracting(ModelCandidate::tiebreakKey)
                .containsExactly("healthy/fallback", "healthy/primary");
        assertThat(router.order(RoutingStrategy.LEAST_COST, candidates))
                .extracting(ModelCandidate::tiebreakKey)
                .containsExactly("healthy/primary", "healthy/fallback");
    }

    @Test
    void routeRejectsBlankAlias() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(candidate("provider", "model", 1L, 1L, true));

        assertThatThrownBy(() -> router.route(" ", RoutingStrategy.FIXED, candidates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("alias is required");
    }

    @Test
    void routeRejectsNullStrategy() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(candidate("provider", "model", 1L, 1L, true));

        assertThatThrownBy(() -> router.route("chat", null, candidates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("strategy is required");
    }

    @Test
    void routeRejectsWhenNoHealthyCandidatesRemainAfterFiltering() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(
                candidate("provider", "offline-a", 1L, 1L, false),
                candidate("provider", "offline-b", 2L, 2L, false));

        assertThatThrownBy(() -> router.route("chat", RoutingStrategy.LEAST_LATENCY, candidates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no healthy candidate for alias 'chat'");
    }

    @Test
    void nonWeightedFallbackOrderingIsDeterministic() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(
                candidate("gamma", "fast", 10L, 30L, true),
                candidate("alpha", "slower", 12L, 20L, false),
                candidate("beta", "fast", 5L, 30L, true),
                candidate("beta", "cheapest", 5L, 40L, true));

        List<ModelCandidate> ordered = router.order(RoutingStrategy.LEAST_COST, candidates);

        assertThat(ordered).extracting(ModelCandidate::tiebreakKey).containsExactly(
                "beta/cheapest", "beta/fast", "gamma/fast");
    }
}
