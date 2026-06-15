package com.example.gateway.quota;

/**
 * Atomic per-tenant, per-period budget counter behind the token/cost budget. Each {@code key}
 * tracks how many tokens and how much cost have been reserved against the period's limits.
 *
 * <p>{@link #reserve} must read the consumed totals, decide whether the new draw fits under
 * <em>both</em> the token and cost limits, and record it only if it does — all as one indivisible
 * operation, so concurrent callers can never both reserve the last fitting slot. This is the
 * contract a Redis {@code HINCRBY}-with-rollback Lua script satisfies on a single instance; the
 * in-memory {@link InMemoryBudgetStore} satisfies it for unit tests. PostgreSQL {@code budgets}
 * reconciliation and distributed proof are the E1 slice.
 *
 * <p>{@link #release} (refund a reservation that was not fully used) and {@link #commit}
 * (reconcile an estimate to actual usage) must keep both consumed totals at or above zero,
 * mirroring the {@code budgets_consumed_nonneg} CHECK — the core invariant of this slice.
 */
public interface BudgetStore {

    /**
     * Atomically reserves {@code tokens} and {@code cost} against {@code key}'s budget.
     *
     * @param key        per-tenant, per-period budget key
     * @param tokens     tokens to reserve (non-negative)
     * @param cost       cost to reserve, in budget micro-units (non-negative)
     * @param tokenLimit maximum tokens allowed in the period
     * @param costLimit  maximum cost allowed in the period
     * @return {@code true} if both draws fit and were recorded; {@code false} if either limit
     *         would be exceeded, in which case nothing is recorded
     */
    boolean reserve(String key, long tokens, long cost, long tokenLimit, long costLimit);

    /**
     * Refunds a previously reserved draw (e.g. the request was rejected downstream or used less
     * than estimated). Neither consumed total may drop below zero.
     */
    void release(String key, long tokens, long cost);

    /**
     * Reconciles a reservation to actual usage: removes the reserved estimate and records the
     * actual draw in one step. Neither consumed total may drop below zero.
     */
    void commit(String key, long reservedTokens, long reservedCost, long actualTokens, long actualCost);
}
