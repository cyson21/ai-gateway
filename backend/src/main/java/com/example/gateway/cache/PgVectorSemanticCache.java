package com.example.gateway.cache;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;

import java.util.Optional;

/**
 * The semantic (embedding similarity) stage of the gateway's {@link SemanticCache}, the C2 path that
 * runs after the exact stage misses. It embeds the prompt with an {@link EmbeddingModel}, asks the
 * {@link SemanticCacheStore} for the cosine-nearest stored answer in the same tenant and response
 * contract, and serves it
 * as a {@link CacheType#SEMANTIC} hit only when the best similarity is at or above the configured
 * {@code similarityThreshold}. Below the threshold it is a miss ({@link CacheType#NONE}) so a
 * semantically different prompt is never served a wrong answer.
 *
 * <p>The threshold is injected (constructor), making the "exactly at threshold passes / just below
 * is rejected" boundary explicit and testable. The store applies strict request metadata
 * partitioning before similarity is considered. This class currently exercises local
 * similarity-cache logic; production pgvector wiring and a real embedding model are not claimed.
 */
public final class PgVectorSemanticCache implements SemanticCache {

    private final EmbeddingModel embeddingModel;
    private final SemanticCacheStore store;
    private final double similarityThreshold;

    /**
     * @param similarityThreshold minimum cosine similarity (inclusive) for a SEMANTIC hit, in
     *     {@code [-1, 1]}; a query at or above it hits, just below it misses
     */
    public PgVectorSemanticCache(EmbeddingModel embeddingModel, SemanticCacheStore store, double similarityThreshold) {
        if (embeddingModel == null || store == null) {
            throw new IllegalArgumentException("embeddingModel and store are required");
        }
        if (similarityThreshold < -1.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException("similarityThreshold must be in [-1, 1]");
        }
        this.embeddingModel = embeddingModel;
        this.store = store;
        this.similarityThreshold = similarityThreshold;
    }

    @Override
    public Lookup lookup(CompletionRequest request) {
        float[] query = embeddingModel.embed(embeddingText(request));
        Optional<SemanticCacheStore.Neighbor> nearest = store.nearest(
                request.tenantId(), CacheRequestFingerprint.responseContract(request), query);
        return nearest
                .filter(neighbor -> neighbor.similarity() >= similarityThreshold)
                .map(neighbor -> new Lookup(CacheType.SEMANTIC, neighbor.response()))
                .orElseGet(Lookup::miss);
    }

    @Override
    public void store(CompletionRequest request, CompletionResponse response) {
        store.add(request.tenantId(), CacheRequestFingerprint.responseContract(request),
                embeddingModel.embed(embeddingText(request)), response);
    }

    /**
     * Material embedded for similarity. Request metadata is enforced by the store partition rather
     * than mixed into the vector, so similarity only compares prompt text inside a compatible
     * response contract.
     */
    private static String embeddingText(CompletionRequest request) {
        return request.prompt();
    }
}
