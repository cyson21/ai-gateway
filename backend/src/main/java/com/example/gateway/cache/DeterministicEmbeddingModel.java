package com.example.gateway.cache;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Deterministic, network-free {@link EmbeddingModel} for unit tests. A prompt is reduced to its set
 * of lowercased word tokens and each token is hashed onto one dimension of a fixed-width vector
 * (bag-of-words), so the embedding is a stable function of the words present — no order, no
 * duplicates, no network, same input always yields the same vector.
 *
 * <p>This makes the semantic-cache contract testable by construction: two prompts that share most of
 * their words point in nearly the same direction (high cosine similarity), while prompts with
 * disjoint vocabularies are near-orthogonal (low similarity). It is a stand-in for a real embedding
 * model, not an approximation of one; production embeddings are out of scope for the C2 slice.
 */
public final class DeterministicEmbeddingModel implements EmbeddingModel {

    /** Smaller than the pgvector(1536) column but identical in contract; the dimension is internal. */
    private final int dimensions;

    public DeterministicEmbeddingModel() {
        this(256);
    }

    public DeterministicEmbeddingModel(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        for (String token : tokenize(text)) {
            int bucket = Math.floorMod(token.hashCode(), dimensions);
            vector[bucket] += 1.0f;
        }
        return vector;
    }

    /** Lowercased, de-duplicated word tokens; punctuation and whitespace are dropped. */
    private static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
            if (!raw.isEmpty()) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    @Override
    public String toString() {
        return "DeterministicEmbeddingModel" + Arrays.toString(new int[] {dimensions});
    }
}
