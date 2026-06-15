package com.example.gateway.cache;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;

import java.util.Optional;

/**
 * The semantic (embedding similarity) stage of the gateway's {@link SemanticCache}, the C2 path that
 * runs after the exact stage misses. It embeds the prompt with an {@link EmbeddingModel}, asks the
 * {@link SemanticCacheStore} for the cosine-nearest stored answer for the same tenant, and serves it
 * as a {@link CacheType#SEMANTIC} hit only when the best similarity is at or above the configured
 * {@code similarityThreshold}. Below the threshold it is a miss ({@link CacheType#NONE}) so a
 * semantically different prompt is never served a wrong answer.
 *
 * <p>The threshold is injected (constructor), making the "exactly at threshold passes / just below
 * is rejected" boundary explicit and testable. Search is tenant-scoped by the store, so tenant
 * isolation holds even when two tenants ask semantically identical questions. Real pgvector wiring
 * (the {@code cache_entries.embedding} column and its ivfflat index) and a real embedding model are
 * the E1 slice; this stage is the similarity-cache logic only.
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
        Optional<SemanticCacheStore.Neighbor> nearest = store.nearest(request.tenantId(), query);
        return nearest
                .filter(neighbor -> neighbor.similarity() >= similarityThreshold)
                .map(neighbor -> new Lookup(CacheType.SEMANTIC, neighbor.response()))
                .orElseGet(Lookup::miss);
    }

    @Override
    public void store(CompletionRequest request, CompletionResponse response) {
        store.add(request.tenantId(), embeddingModel.embed(embeddingText(request)), response);
    }

    /**
     * Material embedded for similarity. The alias is prepended so the same question against a
     * different model alias does not collapse onto another model's cached answer — the alias term
     * shifts the vector away from a different-alias entry, mirroring why the exact stage hashes the
     * alias into {@code prompt_hash}.
     */
    private static String embeddingText(CompletionRequest request) {
        return request.alias() + '\n' + request.prompt();
    }
}
