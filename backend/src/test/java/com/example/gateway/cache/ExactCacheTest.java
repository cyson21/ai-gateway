package com.example.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.LlmProvider;
import com.example.gateway.provider.ToolDefinition;
import com.example.gateway.provider.ToolDefinition.FunctionDefinition;
import com.example.gateway.provider.Usage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the exact prompt-hash cache ({@link ExactCache} over the in-memory
 * {@link CacheStore} fake). The central guarantee is that an identical repeat request is served from
 * cache without calling the provider; the tests prove this by counting provider invocations through
 * the cache-aware flow the pipeline will run. Cross-request isolation (different prompt / alias /
 * tenant must miss) and the whitespace-only normalization are covered too. Real Redis/PostgreSQL
 * persistence and multi-instance proof are the E1 slice.
 */
class ExactCacheTest {

    /** Provider that counts how many real completions it served, to prove cache hits skip it. */
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

    /**
     * Runs the cache-aware completion the pipeline will run: on an EXACT hit return the stored
     * response without touching the provider; on a miss call the provider once and store the result.
     */
    private static CompletionResponse complete(SemanticCache cache, LlmProvider provider, CompletionRequest request) {
        SemanticCache.Lookup lookup = cache.lookup(request);
        if (lookup.hit()) {
            return lookup.response();
        }
        CompletionResponse response = provider.complete("gpt-test", request);
        cache.store(request, response);
        return response;
    }

    private static CompletionRequest request(String tenantId, String alias, String prompt) {
        return new CompletionRequest(tenantId, alias, prompt, 256, false);
    }

    private static CompletionRequest request(String tenantId, String alias, String prompt, int maxTokens,
                                             List<ToolDefinition> tools, String toolChoice) {
        return new CompletionRequest(tenantId, alias, prompt, maxTokens, false, tools, toolChoice);
    }

    @Test
    void firstCallMissesAndCallsProviderSecondCallHitsAndSkipsProvider() {
        ExactCache cache = new ExactCache(new InMemoryCacheStore());
        CountingProvider provider = new CountingProvider();
        CompletionRequest request = request("tenant-1", "gpt-4o", "What is the capital of France?");

        CompletionResponse first = complete(cache, provider, request);
        assertThat(provider.callCount()).isEqualTo(1); // miss -> provider invoked once, then stored

        CompletionResponse second = complete(cache, provider, request);
        assertThat(provider.callCount()).isEqualTo(1); // hit -> provider NOT invoked again
        assertThat(second).isEqualTo(first); // exact same stored response returned
    }

    @Test
    void directLookupReportsExactHitOnlyAfterStore() {
        ExactCache cache = new ExactCache(new InMemoryCacheStore());
        CompletionRequest request = request("tenant-1", "gpt-4o", "ping");

        assertThat(cache.lookup(request).type()).isEqualTo(CacheType.NONE);
        assertThat(cache.lookup(request).hit()).isFalse();

        CompletionResponse stored = new CompletionResponse("pong", "gpt-test", new Usage(1, 1));
        cache.store(request, stored);

        SemanticCache.Lookup hit = cache.lookup(request);
        assertThat(hit.type()).isEqualTo(CacheType.EXACT);
        assertThat(hit.response()).isEqualTo(stored);
    }

    @Test
    void differentPromptAliasOrTenantAreIsolatedMisses() {
        ExactCache cache = new ExactCache(new InMemoryCacheStore());
        CountingProvider provider = new CountingProvider();

        complete(cache, provider, request("tenant-1", "gpt-4o", "hello"));
        assertThat(provider.callCount()).isEqualTo(1);

        // different prompt -> miss
        complete(cache, provider, request("tenant-1", "gpt-4o", "goodbye"));
        assertThat(provider.callCount()).isEqualTo(2);

        // different alias, same prompt -> miss (must not reuse another model's answer)
        complete(cache, provider, request("tenant-1", "claude-3", "hello"));
        assertThat(provider.callCount()).isEqualTo(3);

        // different tenant, same prompt+alias -> miss (tenant isolation)
        complete(cache, provider, request("tenant-2", "gpt-4o", "hello"));
        assertThat(provider.callCount()).isEqualTo(4);

        // repeat of the very first request -> hit, no new provider call
        complete(cache, provider, request("tenant-1", "gpt-4o", "hello"));
        assertThat(provider.callCount()).isEqualTo(4);
    }

    @Test
    void promptsDifferingOnlyInCollapsibleWhitespaceHit() {
        ExactCache cache = new ExactCache(new InMemoryCacheStore());
        CountingProvider provider = new CountingProvider();

        complete(cache, provider, request("tenant-1", "gpt-4o", "tell me a joke"));
        assertThat(provider.callCount()).isEqualTo(1);

        // leading/trailing and collapsible internal whitespace normalize to the same hash -> hit
        CompletionResponse second =
                complete(cache, provider, request("tenant-1", "gpt-4o", "  tell   me\ta\njoke  "));
        assertThat(provider.callCount()).isEqualTo(1);
        assertThat(second.content()).isEqualTo("answer#1:tell me a joke");
    }

    @Test
    void firstWriterWinsOnPutIfAbsent() {
        InMemoryCacheStore store = new InMemoryCacheStore();
        CompletionResponse first = new CompletionResponse("first", "gpt-test", new Usage(1, 1));
        CompletionResponse second = new CompletionResponse("second", "gpt-test", new Usage(1, 1));

        assertThat(store.putIfAbsent("tenant-1", "h", first)).isTrue();
        assertThat(store.putIfAbsent("tenant-1", "h", second)).isFalse(); // entry already present
        assertThat(store.get("tenant-1", "h")).contains(first);
    }

    @Test
    void maxTokenBudgetPartitionsExactResponses() {
        ExactCache cache = new ExactCache(new InMemoryCacheStore());
        CountingProvider provider = new CountingProvider();

        complete(cache, provider, request("tenant-1", "gpt-4o", "hello", 64, List.of(), null));
        complete(cache, provider, request("tenant-1", "gpt-4o", "hello", 128, List.of(), null));

        assertThat(provider.callCount()).isEqualTo(2);
    }

    @Test
    void toolContractAndChoicePartitionExactResponses() {
        ExactCache cache = new ExactCache(new InMemoryCacheStore());
        CountingProvider provider = new CountingProvider();
        ToolDefinition tool = new ToolDefinition("function",
                new FunctionDefinition("lookup", "lookup", Map.of("type", "object")));

        complete(cache, provider, request("tenant-1", "gpt-4o", "hello", 64, List.of(), null));
        complete(cache, provider, request("tenant-1", "gpt-4o", "hello", 64, List.of(tool), "auto"));
        complete(cache, provider, request("tenant-1", "gpt-4o", "hello", 64, List.of(tool), "required"));

        assertThat(provider.callCount()).isEqualTo(3);
    }

    @Test
    void reorderedToolSchemaMapsProduceSameDeterministicFingerprint() {
        Map<String, Object> firstSchema = new LinkedHashMap<>();
        firstSchema.put("type", "object");
        firstSchema.put("required", List.of("id"));
        Map<String, Object> secondSchema = new LinkedHashMap<>();
        secondSchema.put("required", List.of("id"));
        secondSchema.put("type", "object");

        CompletionRequest first = request("tenant-1", "gpt-4o", "hello", 64,
                List.of(new ToolDefinition("function", new FunctionDefinition("lookup", "lookup", firstSchema))),
                "auto");
        CompletionRequest second = request("tenant-1", "gpt-4o", "hello", 64,
                List.of(new ToolDefinition("function", new FunctionDefinition("lookup", "lookup", secondSchema))),
                "auto");

        assertThat(ExactCache.promptHash(first)).isEqualTo(ExactCache.promptHash(second));
    }
}
