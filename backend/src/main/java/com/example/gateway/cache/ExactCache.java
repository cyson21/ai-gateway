package com.example.gateway.cache;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;

import java.util.Optional;

/**
 * Exact prompt-hash cache: the first stage of the gateway's {@link SemanticCache}. After quota
 * passes and before a provider is called, the pipeline asks {@link #lookup}; on an {@link
 * CacheType#EXACT EXACT} hit the stored response is returned and the provider is never invoked. On a
 * miss the pipeline calls the provider and {@link #store stores} the response so the next identical
 * request hits.
 *
 * <p>Two requests are "identical" when they share a tenant and produce the same {@code prompt_hash}.
 * The hash is a SHA-256 over the {@link #normalize normalized} prompt and the model alias, so prompt
 * text that differs only in surrounding or collapsible whitespace still hits. The response contract
 * also includes the alias, token ceiling, tools, and tool choice, while tenant remains the store's
 * separate key dimension. This stage never serves a semantically different prompt.
 *
 * <p>Persistence is delegated to a {@link CacheStore} port — an {@link InMemoryCacheStore} fake for
 * unit tests, a Redis/PostgreSQL {@code cache_entries} implementation in E1 — mirroring the
 * store-port + in-memory-fake pattern of the quota slice.
 */
public final class ExactCache implements SemanticCache {

    private final CacheStore store;

    public ExactCache(CacheStore store) {
        this.store = store;
    }

    @Override
    public Lookup lookup(CompletionRequest request) {
        Optional<CompletionResponse> hit = store.get(request.tenantId(), promptHash(request));
        return hit.map(response -> new Lookup(CacheType.EXACT, response)).orElseGet(Lookup::miss);
    }

    @Override
    public void store(CompletionRequest request, CompletionResponse response) {
        store.putIfAbsent(request.tenantId(), promptHash(request), response);
    }

    /**
     * Stable {@code prompt_hash} for a request: SHA-256 over the response-contract fingerprint and
     * normalized prompt. Tenant remains the store's separate key dimension.
     */
    static String promptHash(CompletionRequest request) {
        String material = CacheRequestFingerprint.responseContract(request) + '\n' + normalize(request.prompt());
        return CacheRequestFingerprint.sha256(material);
    }

    /**
     * Normalizes a prompt for exact matching: trims leading/trailing whitespace and collapses every
     * internal run of whitespace to a single space. Case and content are preserved — only
     * insignificant whitespace is folded, so meaning is never changed.
     */
    static String normalize(String prompt) {
        return prompt.strip().replaceAll("\\s+", " ");
    }

}
