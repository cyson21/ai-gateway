package com.example.gateway.quota;

import com.example.gateway.provider.CompletionRequest;

/**
 * Placeholder {@link CostEstimator} that prices every token at a flat {@code microsPerToken} rate,
 * independent of model. It exists so the cost budget dimension is actually enforced in this slice;
 * the per-model pricing table that replaces it is the router's concern.
 */
public final class FlatRateCostEstimator implements CostEstimator {

    private final long microsPerToken;

    public FlatRateCostEstimator(long microsPerToken) {
        if (microsPerToken < 0) {
            throw new IllegalArgumentException("microsPerToken must be non-negative");
        }
        this.microsPerToken = microsPerToken;
    }

    @Override
    public long estimateCost(CompletionRequest request, int estimatedTokens) {
        long tokens = Math.max(0, estimatedTokens);
        return tokens * microsPerToken;
    }
}
