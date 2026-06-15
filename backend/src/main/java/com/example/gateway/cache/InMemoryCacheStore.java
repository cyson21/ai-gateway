package com.example.gateway.cache;

import com.example.gateway.provider.CompletionResponse;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link CacheStore} mapping {@code (tenantId, promptHash)} to a stored response. The two
 * parts are joined into one composite map key with a length prefix on the tenant id
 * ({@code len|tenantId|promptHash}), so the encoding is injective and distinct tenants never collide
 * on the same prompt hash — the single-instance stand-in for the {@code cache_entries} unique key.
 * {@link #putIfAbsent} delegates to {@link ConcurrentHashMap#putIfAbsent} so the first writer wins
 * atomically. Cross-instance atomicity and persistence are the E1 slice.
 */
public final class InMemoryCacheStore implements CacheStore {

    private final ConcurrentHashMap<String, CompletionResponse> entries = new ConcurrentHashMap<>();

    private static String key(String tenantId, String promptHash) {
        return tenantId.length() + "|" + tenantId + promptHash;
    }

    @Override
    public Optional<CompletionResponse> get(String tenantId, String promptHash) {
        return Optional.ofNullable(entries.get(key(tenantId, promptHash)));
    }

    @Override
    public boolean putIfAbsent(String tenantId, String promptHash, CompletionResponse response) {
        return entries.putIfAbsent(key(tenantId, promptHash), response) == null;
    }
}
