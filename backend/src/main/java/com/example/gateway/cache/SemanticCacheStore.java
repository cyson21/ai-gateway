package com.example.gateway.cache;

import com.example.gateway.provider.CompletionResponse;

import java.util.Optional;

/**
 * Vector store for the semantic cache: per tenant and response contract it keeps
 * {@code (embedding, response)} entries and
 * answers a cosine-similarity nearest-neighbour query. This is the port the pgvector
 * {@code cache_entries.embedding} column with the {@code cache_entries_embedding_idx ivfflat
 * (vector_cosine_ops)} index implements in E1; the {@link InMemorySemanticCacheStore} satisfies it
 * for unit tests with an exhaustive scan.
 *
 * <p>Search is scoped to one tenant and one response contract, so different tenants, aliases, token
 * ceilings, or tool contracts cannot share cached answers. The threshold decision belongs to the
 * caller, not the store.
 */
public interface SemanticCacheStore {

    /** A stored entry's response together with its cosine similarity to the query embedding. */
    record Neighbor(CompletionResponse response, double similarity) {}

    /**
     * Returns the most similar stored response inside the exact tenant/contract partition.
     */
    Optional<Neighbor> nearest(String tenantId, String responseContract, float[] queryEmbedding);

    /** Adds an entry under an exact tenant and response-contract partition. */
    void add(String tenantId, String responseContract, float[] embedding, CompletionResponse response);
}
