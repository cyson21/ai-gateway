package com.example.gateway.cache;

import com.example.gateway.provider.CompletionResponse;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link SemanticCacheStore}: a {@code tenantId -> list of (embedding, response)} map that
 * answers {@link #nearest} with an exhaustive cosine-similarity scan. It is the single-instance
 * stand-in for the pgvector {@code cache_entries} table whose {@code ivfflat (vector_cosine_ops)}
 * index does the same nearest-neighbour search approximately at scale; here the scan is exact, which
 * is what unit tests want.
 *
 * <p>Each tenant has its own entry list (a {@link ConcurrentHashMap} of {@link CopyOnWriteArrayList}),
 * so {@link #nearest} can never return another tenant's entry — tenant isolation holds even for
 * semantically identical prompts. Real persistence, the ivfflat index, eviction, and distributed
 * concurrency are the E1 slice.
 */
public final class InMemorySemanticCacheStore implements SemanticCacheStore {

    private record Entry(float[] embedding, CompletionResponse response) {}

    private final ConcurrentHashMap<String, List<Entry>> byTenant = new ConcurrentHashMap<>();

    @Override
    public Optional<Neighbor> nearest(String tenantId, float[] queryEmbedding) {
        List<Entry> entries = byTenant.get(tenantId);
        if (entries == null) {
            return Optional.empty();
        }
        Neighbor best = null;
        for (Entry entry : entries) {
            double similarity = cosineSimilarity(queryEmbedding, entry.embedding());
            if (best == null || similarity > best.similarity()) {
                best = new Neighbor(entry.response(), similarity);
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public void add(String tenantId, float[] embedding, CompletionResponse response) {
        byTenant.computeIfAbsent(tenantId, t -> new CopyOnWriteArrayList<>())
                .add(new Entry(embedding.clone(), response));
    }

    /**
     * Cosine similarity in {@code [-1, 1]} (the metric behind {@code vector_cosine_ops}). A
     * zero-magnitude vector has no direction, so its similarity to anything is defined as 0 (never a
     * match) to avoid division by zero.
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("embedding dimensions differ: " + a.length + " vs " + b.length);
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
