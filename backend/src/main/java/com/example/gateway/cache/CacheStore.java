package com.example.gateway.cache;

import com.example.gateway.provider.CompletionResponse;

import java.util.Optional;

/**
 * Exact-match cache store keyed by {@code (tenantId, promptHash)}, mirroring the
 * {@code cache_entries_uq UNIQUE (tenant_id, prompt_hash)} constraint. The store keeps a stored
 * {@link CompletionResponse} per prompt hash so a repeated request can be served without calling a
 * provider.
 *
 * <p>{@link #putIfAbsent} is the single atomic operation that backs writes: the first writer for a
 * {@code (tenantId, promptHash)} wins and later writers are ignored, so a race between two misses on
 * the same prompt can never clobber an entry or store divergent responses. This is the contract a
 * Redis {@code SET NX} satisfies on a single instance; the in-memory {@link InMemoryCacheStore}
 * satisfies it for unit tests. Real Redis/PostgreSQL {@code cache_entries} persistence, eviction,
 * and distributed proof are the E1 slice.
 */
public interface CacheStore {

    /** Returns the stored response for {@code (tenantId, promptHash)}, or empty on a miss. */
    Optional<CompletionResponse> get(String tenantId, String promptHash);

    /**
     * Stores {@code response} under {@code (tenantId, promptHash)} only if no entry exists yet.
     *
     * @return {@code true} if this call created the entry; {@code false} if one was already present
     */
    boolean putIfAbsent(String tenantId, String promptHash, CompletionResponse response);
}
