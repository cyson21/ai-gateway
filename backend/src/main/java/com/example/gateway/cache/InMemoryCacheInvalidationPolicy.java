package com.example.gateway.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory tenant/alias cache version map for local tests and demos.
 */
public final class InMemoryCacheInvalidationPolicy implements CacheInvalidationPolicy {

    private final ConcurrentMap<String, AtomicLong> versions = new ConcurrentHashMap<>();

    @Override
    public long version(String tenantId, String alias) {
        return versions.getOrDefault(key(tenantId, alias), new AtomicLong()).get();
    }

    @Override
    public long invalidate(String tenantId, String alias) {
        return versions.computeIfAbsent(key(tenantId, alias), ignored -> new AtomicLong()).incrementAndGet();
    }

    private static String key(String tenantId, String alias) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias is required");
        }
        return tenantId + '\n' + alias;
    }
}
