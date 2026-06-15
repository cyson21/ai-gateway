package com.example.gateway.quota;

/**
 * A tenant's spend budget for one billing {@code period}: at most {@code tokenLimit} tokens and
 * {@code costLimit} cost units may be consumed within it. Mirrors the {@code budgets} row
 * (tenant_id, period, token_limit, cost_limit, consumed) and enforces the same non-negativity the
 * {@code budgets_consumed_nonneg} CHECK guards on the persisted side.
 *
 * <p>{@code costLimit} is denominated in the same micro-unit the {@link CostEstimator} produces
 * (micro-cents in this slice); a zero limit means the dimension is exhausted and every reservation
 * against it is refused.
 */
public record BudgetPolicy(String period, long tokenLimit, long costLimit) {

    public BudgetPolicy {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period is required");
        }
        if (tokenLimit < 0) {
            throw new IllegalArgumentException("tokenLimit must be non-negative");
        }
        if (costLimit < 0) {
            throw new IllegalArgumentException("costLimit must be non-negative");
        }
    }
}
