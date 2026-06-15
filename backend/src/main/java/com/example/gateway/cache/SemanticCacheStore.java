package com.example.gateway.cache;

import com.example.gateway.provider.CompletionResponse;

import java.util.Optional;

/**
 * Vector store for the semantic cache: per tenant it keeps {@code (embedding, response)} entries and
 * answers a cosine-similarity nearest-neighbour query. This is the port the pgvector
 * {@code cache_entries.embedding} column with the {@code cache_entries_embedding_idx ivfflat
 * (vector_cosine_ops)} index implements in E1; the {@link InMemorySemanticCacheStore} satisfies it
 * for unit tests with an exhaustive scan.
 *
 * <p>Search is scoped to one tenant, so a tenant never sees another tenant's cached answers even
 * when the prompts are semantically identical. The store returns the best match and its similarity;
 * the threshold decision (hit vs. miss) belongs to the caller, not the store.
 */
public interface SemanticCacheStore {

    /** A stored entry's response together with its cosine similarity to the query embedding. */
    record Neighbor(CompletionResponse response, double similarity) {}

    /**
     * Returns the most similar stored response for {@code tenantId} by cosine similarity to
     * {@code queryEmbedding}, or empty when the tenant has no entries.
     */
    Optional<Neighbor> nearest(String tenantId, float[] queryEmbedding);

    /** Adds an {@code (embedding, response)} entry under {@code tenantId}. */
    void add(String tenantId, float[] embedding, CompletionResponse response);
}
