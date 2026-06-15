package com.example.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.LlmProvider;
import com.example.gateway.provider.Usage;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TwoStageCache}, the exact (C1) -> semantic (C2) composition behind the single
 * {@link SemanticCache} port. Proves the exact stage short-circuits an identical repeat (C1 behavior
 * preserved: provider not called twice), the semantic stage catches a differently-worded but similar
 * prompt after the exact stage misses, and a semantically different prompt still misses entirely.
 */
class TwoStageCacheTest {

    private static final class CountingProvider implements LlmProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public boolean healthy() {
            return true;
        }

        @Override
        public CompletionResponse complete(String model, CompletionRequest request) {
            int n = calls.incrementAndGet();
            return new CompletionResponse("answer#" + n + ":" + request.prompt(), model, new Usage(3, 5));
        }

        int callCount() {
            return calls.get();
        }
    }

    private static CompletionResponse complete(SemanticCache cache, LlmProvider provider, CompletionRequest request) {
        SemanticCache.Lookup lookup = cache.lookup(request);
        if (lookup.hit()) {
            return lookup.response();
        }
        CompletionResponse response = provider.complete("gpt-test", request);
        cache.store(request, response);
        return response;
    }

    private static CompletionRequest request(String tenantId, String prompt) {
        return new CompletionRequest(tenantId, "gpt-4o", prompt, 256, false);
    }

    private static TwoStageCache twoStage(double threshold) {
        ExactCache exact = new ExactCache(new InMemoryCacheStore());
        PgVectorSemanticCache semantic =
                new PgVectorSemanticCache(new DeterministicEmbeddingModel(), new InMemorySemanticCacheStore(), threshold);
        return new TwoStageCache(exact, semantic);
    }

    @Test
    void identicalRepeatHitsExactlyAndSkipsProvider() {
        TwoStageCache cache = twoStage(0.5);
        CountingProvider provider = new CountingProvider();
        CompletionRequest request = request("tenant-1", "What is the capital of France?");

        CompletionResponse first = complete(cache, provider, request);
        assertThat(provider.callCount()).isEqualTo(1); // miss -> provider once, stored in both stages

        SemanticCache.Lookup repeat = cache.lookup(request);
        assertThat(repeat.type()).isEqualTo(CacheType.EXACT); // exact stage short-circuits first

        CompletionResponse second = complete(cache, provider, request);
        assertThat(provider.callCount()).isEqualTo(1); // C1 guarantee preserved
        assertThat(second).isEqualTo(first);
    }

    @Test
    void differentlyWordedSimilarPromptFallsThroughToSemanticHit() {
        TwoStageCache cache = twoStage(0.5);
        CountingProvider provider = new CountingProvider();

        CompletionResponse first =
                complete(cache, provider, request("tenant-1", "how do I reset my account password"));
        assertThat(provider.callCount()).isEqualTo(1);

        // not exact (different hash) -> exact miss -> semantic catches it
        CompletionRequest reworded = request("tenant-1", "how do I reset the password on my account");
        assertThat(cache.lookup(reworded).type()).isEqualTo(CacheType.SEMANTIC);

        CompletionResponse second = complete(cache, provider, reworded);
        assertThat(provider.callCount()).isEqualTo(1); // served from semantic stage, provider skipped
        assertThat(second).isEqualTo(first);
    }

    @Test
    void semanticallyDifferentPromptMissesBothStages() {
        TwoStageCache cache = twoStage(0.5);
        CountingProvider provider = new CountingProvider();

        complete(cache, provider, request("tenant-1", "how do I reset my account password"));
        assertThat(provider.callCount()).isEqualTo(1);

        CompletionRequest unrelated = request("tenant-1", "what time does the weather change tomorrow");
        assertThat(cache.lookup(unrelated).hit()).isFalse(); // neither exact nor semantic

        complete(cache, provider, unrelated);
        assertThat(provider.callCount()).isEqualTo(2);
    }
}
