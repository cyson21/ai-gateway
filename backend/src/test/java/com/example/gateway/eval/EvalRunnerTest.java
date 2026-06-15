package com.example.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.cache.CacheType;
import com.example.gateway.cache.SemanticCache;
import com.example.gateway.guardrail.Guardrail;
import com.example.gateway.guardrail.GuardrailResult;
import com.example.gateway.observability.RequestRecord;
import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.LlmProvider;
import com.example.gateway.provider.ProviderRegistry;
import com.example.gateway.provider.Usage;
import com.example.gateway.quota.QuotaGuard;
import com.example.gateway.quota.QuotaOutcome;
import com.example.gateway.router.AliasResolver;
import com.example.gateway.router.ModelCandidate;
import com.example.gateway.router.PolicyRouter;
import com.example.gateway.router.RoutingPlan;
import com.example.gateway.router.RoutingStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

/**
 * M5-1: the eval harness runs the deterministic golden set through a <b>real</b>
 * {@link GatewayPipeline} (assembled from controllable fakes per the RequestLogStoreTest pattern)
 * and collects one {@link RequestRecord} per fixture, in order, deterministically.
 *
 * <p>Path divergence is produced by the collaborator fakes, not the request content: a stateful
 * cache (miss-then-hit) drives the duplicate into a cache hit, a label-aware guardrail blocks the
 * banned-input fixture, and a provider that throws for the {@code claude-3} alias drives the
 * fallback/failure path. An injected monotonic clock (+5ms per pipeline call) fixes latency.
 */
class EvalRunnerTest {

    // ---- controllable fakes (RequestLogStoreTest pattern) ------------------

    private static QuotaGuard allowQuota() {
        return (request, estimatedTokens) -> QuotaOutcome.ALLOWED;
    }

    /** Blocks input whenever the prompt contains "leak"; otherwise passes. Deterministic. */
    private static Guardrail bannedTermGuardrail() {
        return new Guardrail() {
            @Override
            public GuardrailResult inspectInput(CompletionRequest request) {
                return request.prompt().contains("leak") ? GuardrailResult.BLOCKED_INPUT : GuardrailResult.PASS;
            }

            @Override
            public GuardrailResult inspectOutput(CompletionResponse response) {
                return GuardrailResult.PASS;
            }
        };
    }

    /**
     * Stateful exact-match cache: misses until a response is stored for the (tenant, alias, prompt)
     * key, then hits with EXACT. Lets the duplicate golden request exercise the cache-hit path.
     */
    private static SemanticCache exactCache() {
        Map<String, CompletionResponse> store = new HashMap<>();
        return new SemanticCache() {
            private String key(CompletionRequest r) {
                return r.tenantId() + "|" + r.alias() + "|" + r.prompt();
            }

            @Override
            public Lookup lookup(CompletionRequest request) {
                CompletionResponse hit = store.get(key(request));
                return hit == null ? new Lookup(CacheType.NONE, null) : new Lookup(CacheType.EXACT, hit);
            }

            @Override
            public void store(CompletionRequest request, CompletionResponse response) {
                store.put(key(request), response);
            }
        };
    }

    /** Alias -> single fixed candidate whose model name equals the alias (so the provider can branch). */
    private static AliasResolver aliasAsModel() {
        return (tenantId, alias) -> new RoutingPlan(
                RoutingStrategy.FIXED,
                List.of(new ModelCandidate("fake", alias, 1000L, 10L, true)));
    }

    private static ProviderRegistry registry(LlmProvider provider) {
        return id -> provider;
    }

    /** Succeeds for every model except "claude-3", which throws to force the failure/fallback path. */
    private static LlmProvider partlyFailingProvider() {
        return new LlmProvider() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public boolean healthy() {
                return true;
            }

            @Override
            public CompletionResponse complete(String model, CompletionRequest request) {
                if ("claude-3".equals(model)) {
                    throw new RuntimeException("provider down for " + model);
                }
                return new CompletionResponse("answer:" + request.prompt(), model, new Usage(7, 11));
            }
        };
    }

    /** Monotonic clock: start=100 on first call, +5ms each later call. Deterministic latency. */
    private static LongSupplier steppingClock() {
        long[] tick = {95L};
        return () -> tick[0] += 5L;
    }

    private static GatewayPipeline freshPipeline() {
        return new GatewayPipeline(allowQuota(), bannedTermGuardrail(), exactCache(),
                aliasAsModel(), new PolicyRouter(), registry(partlyFailingProvider()), 1, steppingClock());
    }

    // ---- golden set determinism --------------------------------------------

    @Test
    void defaultSetIsDeterministicAndNonEmpty() {
        List<GoldenRequest> a = GoldenRequests.defaultSet();
        List<GoldenRequest> b = GoldenRequests.defaultSet();

        assertThat(a).isNotEmpty();
        assertThat(a).isEqualTo(b);
        assertThat(a.stream().map(GoldenRequest::label).distinct().count()).isEqualTo(a.size());
    }

    // ---- collection: one record per fixture, order preserved ---------------

    @Test
    void runCollectsOneRecordPerGoldenInOrder() {
        List<GoldenRequest> set = GoldenRequests.defaultSet();
        EvalResult result = new EvalRunner().run(freshPipeline(), PipelineMode.ROUTED, set);

        assertThat(result.mode()).isEqualTo(PipelineMode.ROUTED);
        assertThat(result.items()).hasSize(set.size());
        assertThat(result.items()).extracting(EvalResult.Item::label)
                .containsExactlyElementsOf(set.stream().map(GoldenRequest::label).toList());
        assertThat(result.records()).hasSize(set.size());
    }

    // ---- path diversity actually appears in the records --------------------

    @Test
    void recordsCoverOkCacheHitGuardrailAndFallbackPaths() {
        List<GoldenRequest> set = GoldenRequests.defaultSet();
        EvalResult result = new EvalRunner().run(freshPipeline(), PipelineMode.ROUTED, set);

        RequestRecord ok = recordFor(result, "g1-normal");
        RequestRecord dup = recordFor(result, "g2-cache-dup");
        RequestRecord banned = recordFor(result, "g3-banned-input");
        RequestRecord fallback = recordFor(result, "g4-fallback");

        // (a) normal ok
        assertThat(ok.message()).isEqualTo("ok");
        assertThat(ok.servedFromCache()).isFalse();
        assertThat(ok.chosenProvider()).isEqualTo("fake");

        // (b) cache hit on the duplicate (g1 stored, g2 reuses same key)
        assertThat(dup.servedFromCache()).isTrue();
        assertThat(dup.cacheType()).isEqualTo(CacheType.EXACT);
        assertThat(dup.chosenProvider()).isEqualTo("cache");

        // (c) input guardrail block
        assertThat(banned.guardrailResult()).isEqualTo(GuardrailResult.BLOCKED_INPUT);
        assertThat(banned.chosenProvider()).isNull();

        // (d) provider failure path (claude-3 throws; ROUTED has no retry budget)
        assertThat(fallback.message()).contains("all candidates failed");
        assertThat(fallback.chosenProvider()).isNull();
    }

    // ---- determinism: two runs are byte-identical (incl. latencyMs) --------

    @Test
    void twoRunsWithSameSetupAreIdenticalIncludingLatency() {
        List<GoldenRequest> set = GoldenRequests.defaultSet();
        EvalResult first = new EvalRunner().run(freshPipeline(), PipelineMode.ROUTED, set);
        EvalResult second = new EvalRunner().run(freshPipeline(), PipelineMode.ROUTED, set);

        assertThat(first).isEqualTo(second);
        assertThat(first.records()).isEqualTo(second.records());
        // latency is fixed at +5ms per handle call (one clock step in, one out)
        assertThat(first.records()).allSatisfy(r -> assertThat(r.latencyMs()).isEqualTo(5L));
    }

    // ---- mode differences: PASSTHROUGH bypasses cache ----------------------

    @Test
    void passthroughBypassesCacheWhileRoutedHits() {
        List<GoldenRequest> set = GoldenRequests.defaultSet();
        EvalRunner runner = new EvalRunner();

        EvalResult routed = runner.run(freshPipeline(), PipelineMode.ROUTED, set);
        EvalResult passthrough = runner.run(freshPipeline(), PipelineMode.PASSTHROUGH, set);

        // ROUTED: duplicate hits the cache populated by g1.
        assertThat(recordFor(routed, "g2-cache-dup").servedFromCache()).isTrue();
        // PASSTHROUGH: cache is bypassed, so the duplicate goes to the provider, no hit.
        RequestRecord dupPassthrough = recordFor(passthrough, "g2-cache-dup");
        assertThat(dupPassthrough.servedFromCache()).isFalse();
        assertThat(dupPassthrough.message()).isEqualTo("ok");
        assertThat(dupPassthrough.chosenProvider()).isEqualTo("fake");
    }

    private static RequestRecord recordFor(EvalResult result, String label) {
        return result.items().stream()
                .filter(i -> i.label().equals(label))
                .map(EvalResult.Item::record)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record for " + label));
    }
}
