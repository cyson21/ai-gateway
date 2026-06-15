package com.example.gateway.quota;

import java.util.Optional;

/**
 * Synchronous lookup of the active spend {@link BudgetPolicy} for a tenant — the {@code budgets}
 * row whose {@code period} covers "now". Like {@link RateLimitPolicyProvider}, the decision is
 * synchronous, so the active budget is expected to be served from an in-memory cache that later
 * slices refresh from the {@code budgets} table; this interface keeps that source pluggable and
 * lets the guard be unit-tested without a database.
 *
 * <p>An empty result means the tenant has no configured budget and is therefore never
 * budget limited by this stage.
 */
@FunctionalInterface
public interface BudgetPolicyProvider {

    Optional<BudgetPolicy> forTenant(String tenantId);
}
