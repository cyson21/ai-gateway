package com.example.gateway.cache;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;

/**
 * Cache wrapper that makes invalidated tenant/alias entries unreachable by shifting the alias key.
 */
public final class VersionedSemanticCache implements SemanticCache {

    private final SemanticCache delegate;
    private final CacheInvalidationPolicy policy;

    public VersionedSemanticCache(SemanticCache delegate, CacheInvalidationPolicy policy) {
        if (delegate == null || policy == null) {
            throw new IllegalArgumentException("delegate and policy are required");
        }
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public Lookup lookup(CompletionRequest request) {
        return delegate.lookup(versioned(request));
    }

    @Override
    public void store(CompletionRequest request, CompletionResponse response) {
        delegate.store(versioned(request), response);
    }

    private CompletionRequest versioned(CompletionRequest request) {
        long version = policy.version(request.tenantId(), request.alias());
        return new CompletionRequest(
                request.tenantId(),
                request.alias() + "@cache-v" + version,
                request.prompt(),
                request.maxTokens(),
                request.stream(),
                request.tools(),
                request.toolChoice());
    }
}
