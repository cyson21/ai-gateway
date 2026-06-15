package com.example.gateway.quota;

import com.example.gateway.provider.CompletionRequest;

/**
 * Projects the cost a request will draw against the {@code cost_limit} budget dimension, in the
 * same micro-unit as {@link BudgetPolicy#costLimit()}. The token dimension is reserved directly
 * from the estimated token count, but cost needs a token-to-money mapping that ultimately belongs
 * to the router's pricing table; this seam keeps that mapping pluggable so the budget guard can be
 * unit-tested and the real pricing slice can drop in later without touching the reservation logic.
 */
@FunctionalInterface
public interface CostEstimator {

    /**
     * @param request         the incoming request (carries the model alias used to price it)
     * @param estimatedTokens estimated prompt+completion tokens for this request
     * @return projected cost in micro-units, never negative
     */
    long estimateCost(CompletionRequest request, int estimatedTokens);
}
