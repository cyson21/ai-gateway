package com.example.gateway.quota;

import java.util.Optional;

/**
 * Synchronous lookup of the rate limit policy in effect for a tenant. The {@link QuotaGuard}
 * decision is synchronous, so policies are expected to be served from an in-memory cache that
 * later slices refresh from the {@code rate_limits} table; this interface keeps that source
 * pluggable and lets the guard be unit-tested without a database.
 *
 * <p>An empty result means the tenant has no configured limit and is therefore never
 * rate limited by this stage.
 */
@FunctionalInterface
public interface RateLimitPolicyProvider {

    Optional<RateLimitPolicy> forTenant(String tenantId);
}
