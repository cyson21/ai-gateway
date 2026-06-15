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
 * M5-2: fold per-mode {@link EvalResult}s into a {@link ModeSummary}/{@link EvalReport} and render
 * a deterministic mode-comparison table. Most cases build {@link RequestRecord}s by hand for exact
 * control over the metric/classification buckets; the last case folds a real multi-mode run from
 * {@link EvalRunner} as a reality smoke check.
 */
class EvalReportTest {

    // ---- hand-built record helpers (one per terminal class) ----------------

    private static RequestRecord ok(int prompt, int completion, long latency, long cost, int fallbacks) {
        return new RequestRecord("t", "gpt-4o", PipelineMode.ROUTED, "fake", "gpt-4o",
                prompt, completion, latency, cost, CacheType.NONE, fallbacks,
                GuardrailResult.PASS, QuotaOutcome.ALLOWED, fallbacks == 0 ? "ok" : "ok after fallback");
    }

    private static RequestRecord cacheHit(int prompt, int completion, long latency) {
        return new RequestRecord("t", "gpt-4o", PipelineMode.ROUTED, "cache", "gpt-4o",
                prompt, completion, latency, 0L, CacheType.EXACT, 0,
                GuardrailResult.PASS, QuotaOutcome.ALLOWED, "served from EXACT cache");
    }

    private static RequestRecord blocked(long latency) {
        return new RequestRecord("t", "gpt-4o", PipelineMode.ROUTED, null, null,
                0, 0, latency, 0L, CacheType.NONE, 0,
                GuardrailResult.BLOCKED_INPUT, QuotaOutcome.ALLOWED, "blocked by input guardrail");
    }

    private static RequestRecord rejected(long latency) {
        return new RequestRecord("t", "gpt-4o", PipelineMode.ROUTED, null, null,
                0, 0, latency, 0L, CacheType.NONE, 0,
                GuardrailResult.PASS, QuotaOutcome.RATE_LIMITED, "rejected by quota: RATE_LIMITED");
    }

    private static RequestRecord failed(long latency, int fallbacks) {
        return new RequestRecord("t", "claude-3", PipelineMode.ROUTED, null, null,
                0, 0, latency, 0L, CacheType.NONE, fallbacks,
                GuardrailResult.PASS, QuotaOutcome.ALLOWED, "all candidates failed: boom");
    }

    private static EvalResult resultOf(PipelineMode mode, RequestRecord... records) {
        List<EvalResult.Item> items = java.util.Arrays.stream(records)
                .map(r -> new EvalResult.Item("g", GoldenRequest.ExpectedClass.OK, r))
                .toList();
        return new EvalResult(mode, items);
    }

    // ---- 1. single-mode metrics are exact sums/counts ----------------------

    @Test
    void foldSumsTokensCostLatencyAndCountsEachBucket() {
        EvalResult result = resultOf(PipelineMode.ROUTED,
                ok(10, 20, 5, 100, 0),
                ok(5, 5, 7, 50, 2),
                cacheHit(3, 4, 1),
                blocked(2),
                rejected(2),
                failed(9, 1));

        ModeSummary s = ModeSummary.fold(result);

        assertThat(s.mode()).isEqualTo(PipelineMode.ROUTED);
        assertThat(s.requestCount()).isEqualTo(6);
        // tokens: prompts 10+5+3 = 18 (blocked/rejected/failed carry 0), completions 20+5+4 = 29
        assertThat(s.totalPromptTokens()).isEqualTo(18L);
        assertThat(s.totalCompletionTokens()).isEqualTo(29L);
        assertThat(s.totalTokens()).isEqualTo(47L);
        assertThat(s.totalCost()).isEqualTo(150L);          // 100 + 50
        assertThat(s.totalLatencyMs()).isEqualTo(5 + 7 + 1 + 2 + 2 + 9L);
        assertThat(s.fallbackCount()).isEqualTo(3L);        // 0 + 2 + 0 + 0 + 0 + 1

        // disjoint buckets partition all 6 records exactly once
        assertThat(s.okCount()).isEqualTo(2);
        assertThat(s.cacheHitCount()).isEqualTo(1);
        assertThat(s.blockedCount()).isEqualTo(1);
        assertThat(s.rejectedCount()).isEqualTo(1);
        assertThat(s.failedCount()).isEqualTo(1);
        assertThat(s.okCount() + s.cacheHitCount() + s.blockedCount()
                + s.rejectedCount() + s.failedCount()).isEqualTo(s.requestCount());
    }

    // ---- 2. ratio determinism + boundaries ---------------------------------

    @Test
    void cacheHitRateIsDeterministicAtBoundaries() {
        // mixed: 2 of 5 hit -> 40.0%
        ModeSummary mixed = ModeSummary.fold(resultOf(PipelineMode.ROUTED,
                cacheHit(1, 1, 1), ok(1, 1, 1, 1, 0), cacheHit(1, 1, 1), ok(1, 1, 1, 1, 0), ok(1, 1, 1, 1, 0)));
        assertThat(mixed.cacheHitRate().numerator()).isEqualTo(2);
        assertThat(mixed.cacheHitRate().denominator()).isEqualTo(5);
        assertThat(mixed.cacheHitRate().perMille()).isEqualTo(400);
        assertThat(mixed.cacheHitRate().asPercent()).isEqualTo("40.0%");

        // all hit -> 100.0%
        ModeSummary all = ModeSummary.fold(resultOf(PipelineMode.ROUTED, cacheHit(1, 1, 1), cacheHit(1, 1, 1)));
        assertThat(all.cacheHitRate().perMille()).isEqualTo(1000);
        assertThat(all.cacheHitRate().asPercent()).isEqualTo("100.0%");

        // none hit -> 0.0%
        ModeSummary none = ModeSummary.fold(resultOf(PipelineMode.ROUTED, ok(1, 1, 1, 1, 0), ok(1, 1, 1, 1, 0)));
        assertThat(none.cacheHitRate().perMille()).isEqualTo(0);
        assertThat(none.cacheHitRate().asPercent()).isEqualTo("0.0%");

        // a repeating fraction truncates deterministically: 1/3 -> 333 per-mille -> 33.3%
        ModeSummary third = ModeSummary.fold(resultOf(PipelineMode.ROUTED,
                cacheHit(1, 1, 1), ok(1, 1, 1, 1, 0), ok(1, 1, 1, 1, 0)));
        assertThat(third.cacheHitRate().perMille()).isEqualTo(333);
        assertThat(third.cacheHitRate().asPercent()).isEqualTo("33.3%");
    }

    // ---- 5. empty mode: no division by zero, safe zeros --------------------

    @Test
    void emptyModeIsSafe() {
        ModeSummary empty = ModeSummary.fold(resultOf(PipelineMode.CACHE_ONLY));

        assertThat(empty.requestCount()).isZero();
        assertThat(empty.totalTokens()).isZero();
        assertThat(empty.totalCost()).isZero();
        assertThat(empty.averageLatencyMs()).isZero();          // no ArithmeticException
        assertThat(empty.cacheHitRate().denominator()).isZero();
        assertThat(empty.cacheHitRate().perMille()).isZero();
        assertThat(empty.cacheHitRate().asPercent()).isEqualTo("0.0%");
    }

    @Test
    void averageLatencyUsesIntegerTruncation() {
        // latencies 5,7,1 -> sum 13, /3 = 4 (truncated)
        ModeSummary s = ModeSummary.fold(resultOf(PipelineMode.ROUTED,
                ok(1, 1, 5, 1, 0), ok(1, 1, 7, 1, 0), ok(1, 1, 1, 1, 0)));
        assertThat(s.totalLatencyMs()).isEqualTo(13L);
        assertThat(s.averageLatencyMs()).isEqualTo(4L);
    }

    // ---- 3. multi-mode report keeps input order, per-row metrics ------------

    @Test
    void reportKeepsModeOrderWithPerRowMetrics() {
        EvalResult routed = resultOf(PipelineMode.ROUTED, ok(10, 10, 5, 100, 0), cacheHit(2, 2, 1));
        EvalResult passthrough = resultOf(PipelineMode.PASSTHROUGH, ok(10, 10, 5, 100, 0), ok(3, 3, 5, 30, 0));

        EvalReport report = EvalReport.of(List.of(routed, passthrough));

        assertThat(report.summaries()).extracting(ModeSummary::mode)
                .containsExactly(PipelineMode.ROUTED, PipelineMode.PASSTHROUGH);

        ModeSummary r = report.summaries().get(0);
        assertThat(r.cacheHitCount()).isEqualTo(1);
        assertThat(r.okCount()).isEqualTo(1);
        assertThat(r.totalCost()).isEqualTo(100L);

        ModeSummary p = report.summaries().get(1);
        assertThat(p.cacheHitCount()).isZero();
        assertThat(p.okCount()).isEqualTo(2);
        assertThat(p.totalCost()).isEqualTo(130L);
    }

    // ---- 4. render determinism: same input -> byte-identical ----------------

    @Test
    void renderIsDeterministicAndByteIdentical() {
        EvalReport report = EvalReport.of(List.of(
                resultOf(PipelineMode.PASSTHROUGH, ok(10, 10, 5, 100, 0), ok(3, 3, 5, 30, 0)),
                resultOf(PipelineMode.ROUTED, ok(10, 10, 5, 100, 0), cacheHit(2, 2, 1))));

        EvalReportRenderer renderer = new EvalReportRenderer();
        String first = renderer.render(report);
        String second = renderer.render(report);

        assertThat(first).isEqualTo(second);
        // header + separator + one row per mode (in input order)
        String[] lines = first.split("\n", -1);
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).startsWith("| mode | requests | ok |");
        assertThat(lines[1]).isEqualTo("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");
        assertThat(lines[2]).startsWith("| PASSTHROUGH | 2 |");
        assertThat(lines[3]).startsWith("| ROUTED | 2 |");
        // cache hit rate column renders the deterministic percent for ROUTED (1 of 2 -> 50.0%)
        assertThat(lines[3]).contains("50.0%");
    }

    @Test
    void renderEmptyReportIsHeaderAndSeparatorOnly() {
        String out = new EvalReportRenderer().render(new EvalReport(List.of()));
        String[] lines = out.split("\n", -1);
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("| mode |");
        assertThat(lines[1]).startsWith("| --- |");
    }

    // ---- 6. reality smoke: fold a real multi-mode EvalRunner run ------------

    @Test
    void realMultiModeRunFoldsAndRendersConsistently() {
        List<GoldenRequest> set = GoldenRequests.defaultSet();
        EvalRunner runner = new EvalRunner();
        List<EvalResult> results = List.of(
                runner.run(freshPipeline(), PipelineMode.ROUTED, set),
                runner.run(freshPipeline(), PipelineMode.PASSTHROUGH, set));

        EvalReport report = EvalReport.of(results);
        assertThat(report.summaries()).extracting(ModeSummary::mode)
                .containsExactly(PipelineMode.ROUTED, PipelineMode.PASSTHROUGH);

        // every mode folded all 5 golden fixtures, buckets partition them
        for (ModeSummary s : report.summaries()) {
            assertThat(s.requestCount()).isEqualTo(set.size());
            assertThat(s.okCount() + s.cacheHitCount() + s.blockedCount()
                    + s.rejectedCount() + s.failedCount()).isEqualTo(set.size());
        }

        // ROUTED: g2 duplicate hits cache; PASSTHROUGH bypasses cache -> no hit.
        ModeSummary routed = report.summaries().get(0);
        ModeSummary passthrough = report.summaries().get(1);
        assertThat(routed.cacheHitCount()).isEqualTo(1);
        assertThat(passthrough.cacheHitCount()).isZero();
        // both modes block the banned-input golden once
        assertThat(routed.blockedCount()).isEqualTo(1);
        assertThat(passthrough.blockedCount()).isEqualTo(1);

        // render is stable across repeated calls on the real report
        EvalReportRenderer renderer = new EvalReportRenderer();
        assertThat(renderer.render(report)).isEqualTo(renderer.render(report));
    }

    // ---- real pipeline assembly (mirrors EvalRunnerTest fakes) --------------

    private static GatewayPipeline freshPipeline() {
        return new GatewayPipeline(allowQuota(), bannedTermGuardrail(), exactCache(),
                aliasAsModel(), new PolicyRouter(), registry(partlyFailingProvider()), 1, steppingClock());
    }

    private static QuotaGuard allowQuota() {
        return (request, estimatedTokens) -> QuotaOutcome.ALLOWED;
    }

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

    private static AliasResolver aliasAsModel() {
        return (tenantId, alias) -> new RoutingPlan(
                RoutingStrategy.FIXED,
                List.of(new ModelCandidate("fake", alias, 1000L, 10L, true)));
    }

    private static ProviderRegistry registry(LlmProvider provider) {
        return id -> provider;
    }

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

    private static LongSupplier steppingClock() {
        long[] tick = {95L};
        return () -> tick[0] += 5L;
    }
}
