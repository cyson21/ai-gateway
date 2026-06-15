package com.example.gateway.quota;

import com.example.gateway.provider.CompletionRequest;

/**
 * Quota stage: rate limit plus token/cost budget. The foundation harness uses a
 * synchronous decision; the production slice backs this with Redis sliding-window
 * counters and atomic budget reservations, with PostgreSQL as the final record.
 */
@FunctionalInterface
public interface QuotaGuard {

    /**
     * @param request          the incoming request
     * @param estimatedTokens  estimated prompt+completion tokens for budget reservation
     * @return ALLOWED, RATE_LIMITED, or BUDGET_EXCEEDED
     */
    QuotaOutcome evaluate(CompletionRequest request, int estimatedTokens);
}
