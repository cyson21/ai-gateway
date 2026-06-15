package com.example.gateway.cache;

/**
 * Turns a prompt into a dense embedding vector for semantic similarity search. Real embedding
 * models (OpenAI {@code text-embedding-*}, etc.) implement this behind the same port; the gateway's
 * semantic cache only depends on this seam, never on a vendor SDK or the network.
 *
 * <p>For unit tests a {@link DeterministicEmbeddingModel} produces a stable, network-free vector so
 * "semantically similar" prompts land close together by construction. Real embedding wiring is out
 * of scope for the C2 slice.
 */
public interface EmbeddingModel {

    /** Embeds {@code text} into a fixed-dimension vector. */
    float[] embed(String text);
}
