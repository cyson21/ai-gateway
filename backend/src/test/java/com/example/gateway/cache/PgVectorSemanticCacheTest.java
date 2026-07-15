package com.example.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.LlmProvider;
import com.example.gateway.provider.ToolDefinition;
import com.example.gateway.provider.ToolDefinition.FunctionDefinition;
import com.example.gateway.provider.Usage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the semantic (embedding similarity) cache stage ({@link PgVectorSemanticCache} over
 * the {@link DeterministicEmbeddingModel} and the {@link InMemorySemanticCacheStore} fake). The core
 * guarantees: a differently-worded but semantically similar prompt hits above the threshold and is
 * served the stored answer without calling the provider; a semantically different (below-threshold)
 * prompt misses and the provider runs; the threshold boundary admits exactly-at and rejects
 * just-below; and search is tenant-isolated. Real pgvector/embedding wiring is the E1 slice.
 */
class PgVectorSemanticCacheTest {

    /** Provider that counts completions so cache hits (which skip it) are observable. */
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

    /** The cache-aware flow the pipeline runs: hit -> stored response; miss -> call provider, store. */
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

    private static CompletionRequest request(String tenantId, String alias, String prompt, int maxTokens,
                                             List<ToolDefinition> tools, String toolChoice) {
        return new CompletionRequest(tenantId, alias, prompt, maxTokens, false, tools, toolChoice);
    }

    private static PgVectorSemanticCache cache(double threshold) {
        return new PgVectorSemanticCache(new DeterministicEmbeddingModel(), new InMemorySemanticCacheStore(), threshold);
    }

    @Test
    void differentlyWordedButSimilarPromptHitsAboveThreshold() {
        PgVectorSemanticCache cache = cache(0.5);
        CountingProvider provider = new CountingProvider();

        CompletionResponse first =
                complete(cache, provider, request("tenant-1", "how do I reset my account password"));
        assertThat(provider.callCount()).isEqualTo(1); // miss -> provider, then stored

        // same meaning, reworded/reordered, shares most word tokens -> high cosine -> SEMANTIC hit
        CompletionResponse second =
                complete(cache, provider, request("tenant-1", "how do I reset the password on my account"));
        assertThat(provider.callCount()).isEqualTo(1); // hit -> provider NOT called again
        assertThat(second).isEqualTo(first); // stored answer served
    }

    @Test
    void semanticallyDifferentPromptMissesAndCallsProvider() {
        PgVectorSemanticCache cache = cache(0.5);
        CountingProvider provider = new CountingProvider();

        complete(cache, provider, request("tenant-1", "how do I reset my account password"));
        assertThat(provider.callCount()).isEqualTo(1);

        // disjoint vocabulary -> near-orthogonal -> below threshold -> miss -> provider runs
        complete(cache, provider, request("tenant-1", "what time does the weather change tomorrow"));
        assertThat(provider.callCount()).isEqualTo(2);
    }

    @Test
    void lookupReportsSemanticHitOnlyAfterStore() {
        PgVectorSemanticCache cache = cache(0.5);
        CompletionRequest request = request("tenant-1", "summarize the quarterly revenue report");

        assertThat(cache.lookup(request).type()).isEqualTo(CacheType.NONE);

        CompletionResponse stored = new CompletionResponse("summary", "gpt-test", new Usage(1, 1));
        cache.store(request, stored);

        SemanticCache.Lookup hit = cache.lookup(request("tenant-1", "please summarize the quarterly report on revenue"));
        assertThat(hit.type()).isEqualTo(CacheType.SEMANTIC);
        assertThat(hit.response()).isEqualTo(stored);
    }

    @Test
    void thresholdBoundaryAdmitsExactlyAtAndRejectsJustBelow() {
        // Two prompts sharing 3 of 4 tokens each: cosine = 3 / 4 = 0.75 exactly (orthogonal extras).
        SemanticCache.Lookup stored;
        InMemorySemanticCacheStore store = new InMemorySemanticCacheStore();
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();
        CompletionResponse response = new CompletionResponse("cached", "gpt-test", new Usage(1, 1));

        CompletionRequest base = request("tenant-1", "alpha beta gamma delta");
        CompletionRequest query = request("tenant-1", "alpha beta gamma epsilon");
        double similarity = InMemorySemanticCacheStore.cosineSimilarity(
                model.embed("alpha beta gamma delta"), model.embed("alpha beta gamma epsilon"));

        // threshold exactly at the measured similarity -> hit (>= is inclusive)
        PgVectorSemanticCache atThreshold = new PgVectorSemanticCache(model, store, similarity);
        atThreshold.store(base, response);
        assertThat(atThreshold.lookup(query).type()).isEqualTo(CacheType.SEMANTIC);

        // threshold a hair above the same similarity -> just-below -> miss
        PgVectorSemanticCache aboveThreshold =
                new PgVectorSemanticCache(model, new InMemorySemanticCacheStore(), Math.nextUp(similarity));
        aboveThreshold.store(base, response);
        assertThat(aboveThreshold.lookup(query).type()).isEqualTo(CacheType.NONE);
    }

    @Test
    void tenantsAreIsolatedEvenForIdenticalPrompts() {
        PgVectorSemanticCache cache = cache(0.5);
        CountingProvider provider = new CountingProvider();

        complete(cache, provider, request("tenant-1", "how do I reset my account password"));
        assertThat(provider.callCount()).isEqualTo(1);

        // tenant-2 asks the very same question -> must not see tenant-1's cache -> miss -> provider
        complete(cache, provider, request("tenant-2", "how do I reset my account password"));
        assertThat(provider.callCount()).isEqualTo(2);

        // tenant-1 repeats -> still hits its own cache
        complete(cache, provider, request("tenant-1", "how do I reset my account password"));
        assertThat(provider.callCount()).isEqualTo(2);
    }

    @Test
    void aliasesAreStrictlyPartitionedInsteadOfDependingOnVectorDistance() {
        PgVectorSemanticCache cache = cache(0.1);
        CompletionResponse response = new CompletionResponse("cached", "model-a", new Usage(1, 1));
        cache.store(request("tenant-1", "alias-a", "shared prompt", 64, List.of(), null), response);

        assertThat(cache.lookup(request("tenant-1", "alias-b", "shared prompt", 64, List.of(), null)).hit())
                .isFalse();
    }

    @Test
    void tokenAndToolContractsAreStrictlyPartitioned() {
        PgVectorSemanticCache cache = cache(0.1);
        ToolDefinition tool = new ToolDefinition("function",
                new FunctionDefinition("lookup", "lookup", Map.of("type", "object")));
        CompletionRequest stored = request("tenant-1", "gpt-4o", "shared prompt", 64, List.of(tool), "auto");
        cache.store(stored, new CompletionResponse("cached", "model-a", new Usage(1, 1)));

        assertThat(cache.lookup(request(
                "tenant-1", "gpt-4o", "shared prompt", 128, List.of(tool), "auto")).hit()).isFalse();
        assertThat(cache.lookup(request(
                "tenant-1", "gpt-4o", "shared prompt", 64, List.of(tool), "required")).hit()).isFalse();
        assertThat(cache.lookup(request(
                "tenant-1", "gpt-4o", "shared prompt", 64, List.of(), null)).hit()).isFalse();
    }
}
