package com.example.gateway.cache;

/**
 * Version source for tenant/alias cache invalidation.
 */
public interface CacheInvalidationPolicy {

    long version(String tenantId, String alias);

    long invalidate(String tenantId, String alias);
}
