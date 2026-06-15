package com.example.gateway.cache;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;

/**
 * Composes the exact stage (C1) and the semantic stage (C2) behind the single {@link SemanticCache}
 * port without reimplementing either. {@link #lookup} tries the exact stage first; on an
 * {@link CacheType#EXACT EXACT} hit it returns immediately (cheap, identical-prompt path), otherwise
 * it falls through to the semantic stage, which yields a {@link CacheType#SEMANTIC} hit above the
 * similarity threshold or a miss. Composing this way preserves the C1 guarantee — an identical
 * repeat request still hits exactly and never calls the provider.
 *
 * <p>{@link #store} writes to <em>both</em> stages so a stored answer is reachable by a later
 * identical request (exact) and by a later differently-worded but semantically similar request
 * (semantic). The two delegates are usually an {@link ExactCache} and a {@link PgVectorSemanticCache},
 * but any {@link SemanticCache} pair composes — the exact delegate just has to return EXACT/NONE.
 */
public final class TwoStageCache implements SemanticCache {

    private final SemanticCache exact;
    private final SemanticCache semantic;

    public TwoStageCache(SemanticCache exact, SemanticCache semantic) {
        if (exact == null || semantic == null) {
            throw new IllegalArgumentException("exact and semantic stages are required");
        }
        this.exact = exact;
        this.semantic = semantic;
    }

    @Override
    public Lookup lookup(CompletionRequest request) {
        Lookup exactLookup = exact.lookup(request);
        if (exactLookup.hit()) {
            return exactLookup;
        }
        return semantic.lookup(request);
    }

    @Override
    public void store(CompletionRequest request, CompletionResponse response) {
        exact.store(request, response);
        semantic.store(request, response);
    }
}
