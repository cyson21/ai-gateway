package com.example.gateway.cache;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;

/**
 * Two-stage cache: exact prompt-hash match (Redis) then semantic similarity (pgvector).
 * A miss returns {@link CacheType#NONE}. Hits must be above the configured threshold so
 * a semantically different prompt is never served a wrong answer.
 */
public interface SemanticCache {

    /** Result of a cache lookup. {@code response} is null when {@code type} is NONE. */
    record Lookup(CacheType type, CompletionResponse response) {
        public static Lookup miss() {
            return new Lookup(CacheType.NONE, null);
        }

        public boolean hit() {
            return type != CacheType.NONE;
        }
    }

    Lookup lookup(CompletionRequest request);

    void store(CompletionRequest request, CompletionResponse response);
}
