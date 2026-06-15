package com.example.gateway.router;

/**
 * Resolves a tenant's model alias to its routing plan (strategy + declared candidates).
 * Backed by {@code model_aliases} and {@code routing_policies} in the production slice.
 */
@FunctionalInterface
public interface AliasResolver {

    RoutingPlan resolve(String tenantId, String alias);
}
