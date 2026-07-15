package com.example.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.api.GatewayResult;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.cache.CacheType;
import com.example.gateway.cache.SemanticCache;
import com.example.gateway.guardrail.Guardrail;
import com.example.gateway.guardrail.GuardrailResult;
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

import java.util.List;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

/**
 * D1: every {@link RequestRecord} the pipeline emits must land as one {@code request_logs} row.
 *
 * <p>The all-paths-record contract is proven by driving a <b>real</b> {@link GatewayPipeline} with
 * controllable fakes down each path (quota reject, input guardrail block, cache hit, failed, ok),
 * feeding each produced record into the sink, and asserting a row is created and the columns are the
 * record's projection (enum -> name(), null provider/model preserved). The store's tenant-isolation
 * and multi-row accumulation contract is exercised directly.
 */
class RequestLogStoreTest {

    // ---- controllable fakes ------------------------------------------------

    private static QuotaGuard quota(QuotaOutcome outcome) {
        return (request, estimatedTokens) -> outcome;
    }

    private static Guardrail guardrail(GuardrailResult in, GuardrailResult out) {
        return new Guardrail() {
            @Override
            public GuardrailResult inspectInput(CompletionRequest request) {
                return in;
            }

            @Override
            public GuardrailResult inspectOutput(CompletionResponse response) {
                return out;
            }
        };
    }

    /** Cache that always misses (NONE) — pushes the pipeline to the router/provider path. */
    private static SemanticCache missCache() {
        return new SemanticCache() {
            @Override
            public Lookup lookup(CompletionRequest request) {
                return new Lookup(CacheType.NONE, null);
            }

            @Override
            public void store(CompletionRequest request, CompletionResponse response) {
            }
        };
    }

    /** Cache that always hits with the given type. */
    private static SemanticCache hitCache(CacheType type) {
        return new SemanticCache() {
            @Override
            public Lookup lookup(CompletionRequest request) {
                return new Lookup(type, new CompletionResponse("cached answer", "cached-model", new Usage(2, 4)));
            }

            @Override
            public void store(CompletionRequest request, CompletionResponse response) {
            }
        };
    }

    private static AliasResolver fixedPlan(ModelCandidate... candidates) {
        RoutingPlan plan = new RoutingPlan(RoutingStrategy.FIXED, List.of(candidates));
        return (tenantId, alias) -> plan;
    }

    private static final ModelCandidate FAKE_CANDIDATE =
            new ModelCandidate("fake", "fake-model", 1000L, 10L, true);

    private static ProviderRegistry registry(LlmProvider provider) {
        return id -> provider;
    }

    private static LlmProvider okProvider() {
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
                return new CompletionResponse("answer:" + request.prompt(), model, new Usage(7, 11));
            }
        };
    }

    /** Fixed clock so latency is a known, deterministic value. */
    private static LongSupplier fixedClock() {
        // first call -> start=100, every later call -> 105, so elapsed = 5ms.
        long[] calls = {0L};
        return () -> (++calls[0] == 1) ? 100L : 105L;
    }

    private static GatewayPipeline pipeline(QuotaGuard quota, Guardrail guardrail, SemanticCache cache,
                                            AliasResolver resolver, LlmProvider provider) {
        return new GatewayPipeline(quota, guardrail, cache, resolver, new PolicyRouter(),
                registry(provider), 1, fixedClock());
    }

    private static CompletionRequest request(String tenant) {
        return new CompletionRequest(tenant, "gpt-4o", "hello world", 64, false);
    }

    // ---- all-paths-record: each pipeline path creates exactly one row -------

    @Test
    void quotaRejectPathCreatesRowWithNullProviderAndOutcome() {
        RequestLogStore store = new InMemoryRequestLogStore();
        GatewayPipeline p = pipeline(quota(QuotaOutcome.RATE_LIMITED), guardrail(GuardrailResult.PASS, GuardrailResult.PASS),
                missCache(), fixedPlan(FAKE_CANDIDATE), okProvider());

        RequestRecord record = p.handle(request("tenant-1"), PipelineMode.ROUTED);
        RequestLogRow row = store.append(record);

        assertThat(store.all()).hasSize(1);
        assertThat(record.budgetOutcome()).isEqualTo(QuotaOutcome.RATE_LIMITED);
        assertThat(row.tenantId()).isEqualTo("tenant-1");
        assertThat(row.mode()).isEqualTo("ROUTED");
        assertThat(row.budgetOutcome()).isEqualTo("RATE_LIMITED");
        assertThat(row.chosenProvider()).isNull();
        assertThat(row.chosenModel()).isNull();
        assertThat(row.cacheType()).isEqualTo("NONE");
        assertThat(row.promptTokens()).isZero();
    }

    @Test
    void budgetExceededPathCreatesRow() {
        RequestLogStore store = new InMemoryRequestLogStore();
        GatewayPipeline p = pipeline(quota(QuotaOutcome.BUDGET_EXCEEDED), guardrail(GuardrailResult.PASS, GuardrailResult.PASS),
                missCache(), fixedPlan(FAKE_CANDIDATE), okProvider());

        RequestLogRow row = store.append(p.handle(request("tenant-1"), PipelineMode.ROUTED));

        assertThat(store.all()).hasSize(1);
        assertThat(row.budgetOutcome()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(row.chosenProvider()).isNull();
    }

    @Test
    void inputGuardrailBlockPathCreatesRowWithNullProvider() {
        RequestLogStore store = new InMemoryRequestLogStore();
        GatewayPipeline p = pipeline(quota(QuotaOutcome.ALLOWED), guardrail(GuardrailResult.BLOCKED_INPUT, GuardrailResult.PASS),
                missCache(), fixedPlan(FAKE_CANDIDATE), okProvider());

        RequestLogRow row = store.append(p.handle(request("tenant-1"), PipelineMode.ROUTED));

        assertThat(store.all()).hasSize(1);
        assertThat(row.guardrailResult()).isEqualTo("BLOCKED_INPUT");
        assertThat(row.budgetOutcome()).isEqualTo("ALLOWED");
        assertThat(row.chosenProvider()).isNull();
        assertThat(row.chosenModel()).isNull();
    }

    @Test
    void cacheHitPathCreatesRowWithCacheProviderAndZeroCost() {
        RequestLogStore store = new InMemoryRequestLogStore();
        GatewayPipeline p = pipeline(quota(QuotaOutcome.ALLOWED), guardrail(GuardrailResult.PASS, GuardrailResult.PASS),
                hitCache(CacheType.SEMANTIC), fixedPlan(FAKE_CANDIDATE), okProvider());

        RequestRecord record = p.handle(request("tenant-1"), PipelineMode.ROUTED);
        RequestLogRow row = store.append(record);

        assertThat(store.all()).hasSize(1);
        assertThat(record.servedFromCache()).isTrue();
        assertThat(row.cacheType()).isEqualTo("SEMANTIC");
        assertThat(row.chosenProvider()).isEqualTo("cache");
        assertThat(row.cost()).isZero();
        assertThat(row.budgetOutcome()).isEqualTo("ALLOWED");
    }

    @Test
    void cacheHitIsRecheckedByOutputGuardrailBeforeServing() {
        GatewayPipeline p = pipeline(quota(QuotaOutcome.ALLOWED),
                guardrail(GuardrailResult.PASS, GuardrailResult.BLOCKED_OUTPUT),
                hitCache(CacheType.EXACT), fixedPlan(FAKE_CANDIDATE), okProvider());

        GatewayResult result = p.execute(request("tenant-1"), PipelineMode.ROUTED);

        assertThat(result.hasResponse()).isFalse();
        assertThat(result.record().guardrailResult()).isEqualTo(GuardrailResult.BLOCKED_OUTPUT);
        assertThat(result.record().cacheType()).isEqualTo(CacheType.EXACT);
        assertThat(result.record().chosenProvider()).isEqualTo("cache");
        assertThat(result.record().message()).isEqualTo("cached response blocked by output guardrail");
    }

    @Test
    void failedNoCandidatePathCreatesRowWithNullProvider() {
        RequestLogStore store = new InMemoryRequestLogStore();
        // no candidates -> ordered empty -> failed path
        GatewayPipeline p = pipeline(quota(QuotaOutcome.ALLOWED), guardrail(GuardrailResult.PASS, GuardrailResult.PASS),
                missCache(), fixedPlan(), okProvider());

        RequestLogRow row = store.append(p.handle(request("tenant-1"), PipelineMode.ROUTED));

        assertThat(store.all()).hasSize(1);
        assertThat(row.chosenProvider()).isNull();
        assertThat(row.chosenModel()).isNull();
        assertThat(row.guardrailResult()).isEqualTo("PASS");
        assertThat(row.message()).contains("no healthy candidate");
    }

    @Test
    void okPathCreatesRowWithFullColumnProjection() {
        RequestLogStore store = new InMemoryRequestLogStore();
        GatewayPipeline p = pipeline(quota(QuotaOutcome.ALLOWED), guardrail(GuardrailResult.PASS, GuardrailResult.PASS),
                missCache(), fixedPlan(FAKE_CANDIDATE), okProvider());

        RequestRecord record = p.handle(request("tenant-1"), PipelineMode.ROUTED);
        RequestLogRow row = store.append(record);

        assertThat(store.all()).hasSize(1);
        // column projection 1:1 with the record
        assertThat(row.tenantId()).isEqualTo(record.tenantId());
        assertThat(row.alias()).isEqualTo(record.alias());
        assertThat(row.mode()).isEqualTo("ROUTED");
        assertThat(row.chosenProvider()).isEqualTo("fake");
        assertThat(row.chosenModel()).isEqualTo("fake-model");
        assertThat(row.promptTokens()).isEqualTo(record.promptTokens()).isEqualTo(7);
        assertThat(row.completionTokens()).isEqualTo(record.completionTokens()).isEqualTo(11);
        assertThat(row.latencyMs()).isEqualTo(record.latencyMs()).isEqualTo(5L);
        assertThat(row.cost()).isEqualTo(record.cost());
        assertThat(row.cacheType()).isEqualTo("NONE");
        assertThat(row.guardrailResult()).isEqualTo("PASS");
        assertThat(row.budgetOutcome()).isEqualTo("ALLOWED");
        assertThat(row.fallbackCount()).isEqualTo(record.fallbackCount());
    }

    // ---- store contract: isolation + accumulation --------------------------

    @Test
    void rowsAreIsolatedAndAccumulatedPerTenant() {
        RequestLogStore store = new InMemoryRequestLogStore();
        GatewayPipeline p = pipeline(quota(QuotaOutcome.ALLOWED), guardrail(GuardrailResult.PASS, GuardrailResult.PASS),
                missCache(), fixedPlan(FAKE_CANDIDATE), okProvider());

        store.append(p.handle(request("tenant-1"), PipelineMode.ROUTED));
        store.append(p.handle(request("tenant-1"), PipelineMode.ROUTED));
        store.append(p.handle(request("tenant-2"), PipelineMode.ROUTED));

        assertThat(store.all()).hasSize(3);
        assertThat(store.forTenant("tenant-1")).hasSize(2);
        assertThat(store.forTenant("tenant-2")).hasSize(1);
        assertThat(store.forTenant("tenant-3")).isEmpty();
        assertThat(store.forTenant("tenant-1")).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo("tenant-1"));
    }

    @Test
    void appendUsesInjectedClockForCreatedAt() {
        RequestLogStore store = new InMemoryRequestLogStore();
        RequestRecord record = new RequestRecord("tenant-1", "gpt-4o", PipelineMode.ROUTED,
                null, null, 0, 0, 5L, 0L, CacheType.NONE, 0,
                GuardrailResult.PASS, QuotaOutcome.ALLOWED, "rejected by quota: RATE_LIMITED");

        RequestLogRow row = store.append(record, () -> 42L);

        assertThat(row.createdAtMillis()).isEqualTo(42L);
        assertThat(row.message()).isEqualTo("rejected by quota: RATE_LIMITED");
    }
}
