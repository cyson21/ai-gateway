package com.example.gateway.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.provider.CompletionRequest;

import java.util.List;

import org.junit.jupiter.api.Test;

class WeightedRoutingTest {

    private static ModelCandidate candidate(String provider, String model, int weight) {
        return new ModelCandidate(provider, model, 100L, 10L, true, weight);
    }

    private static CompletionRequest requestWithPrompt(String prompt) {
        return new CompletionRequest("tenant-1", "ab-test", prompt, 64, false);
    }

    @Test
    void weightedSplitPicksPrimaryFromStableRequestBucketAndKeepsFallbackOrder() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(
                candidate("fake", "blue", 1),
                candidate("fake", "green", 1));

        List<ModelCandidate> forEvenBucket = router.order(
                RoutingStrategy.WEIGHTED_SPLIT, candidates, requestWithPrompt("b"));
        List<ModelCandidate> forOddBucket = router.order(
                RoutingStrategy.WEIGHTED_SPLIT, candidates, requestWithPrompt("a"));

        assertThat(forEvenBucket).extracting(ModelCandidate::model).containsExactly("blue", "green");
        assertThat(forOddBucket).extracting(ModelCandidate::model).containsExactly("green", "blue");
    }

    @Test
    void zeroWeightCandidateIsNeverPrimaryButRemainsFallbackCandidate() {
        PolicyRouter router = new PolicyRouter();
        List<ModelCandidate> candidates = List.of(
                candidate("fake", "disabled-primary", 0),
                candidate("fake", "active-a", 1),
                candidate("fake", "active-b", 1));

        List<ModelCandidate> ordered = router.order(
                RoutingStrategy.WEIGHTED_SPLIT, candidates, requestWithPrompt("a"));

        assertThat(ordered.get(0).trafficWeight()).isPositive();
        assertThat(ordered).extracting(ModelCandidate::model).contains("disabled-primary");
    }
}
