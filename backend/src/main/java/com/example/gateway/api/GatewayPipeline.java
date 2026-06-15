package com.example.gateway.api;

import com.example.gateway.cache.CacheType;
import com.example.gateway.cache.SemanticCache;
import com.example.gateway.guardrail.Guardrail;
import com.example.gateway.guardrail.GuardrailResult;
import com.example.gateway.observability.RequestRecord;
import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.ProviderRegistry;
import com.example.gateway.quota.QuotaGuard;
import com.example.gateway.quota.QuotaOutcome;
import com.example.gateway.resilience.CircuitBreaker;
import com.example.gateway.resilience.FallbackChain;
import com.example.gateway.resilience.FallbackResult;
import com.example.gateway.resilience.RetryBudget;
import com.example.gateway.router.AliasResolver;
import com.example.gateway.router.ModelCandidate;
import com.example.gateway.router.PolicyRouter;
import com.example.gateway.router.RoutingPlan;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Synchronous foundation harness for the gateway pipeline:
 *
 * <pre>quota -&gt; guardrail(in) -&gt; cache -&gt; router -&gt; dispatch -&gt; fallback -&gt; guardrail(out) -&gt; record</pre>
 *
 * Authentication (API key -&gt; tenant) happens before this harness; the request already
 * carries a resolved {@code tenantId}. Every path returns a fully populated
 * {@link RequestRecord}. Streaming is layered on in a later slice via the reactive web layer.
 */
public final class GatewayPipeline {

    private final QuotaGuard quotaGuard;
    private final Guardrail guardrail;
    private final SemanticCache cache;
    private final AliasResolver aliasResolver;
    private final PolicyRouter router;
    private final int maxAttempts;
    private final FallbackChain fallbackChain;
    private final CircuitBreaker circuitBreaker;
    private final RetryBudget retryBudget;
    private final LongSupplier clockMillis;

    public GatewayPipeline(QuotaGuard quotaGuard, Guardrail guardrail, SemanticCache cache,
                           AliasResolver aliasResolver, PolicyRouter router, ProviderRegistry providers,
                           int retryBudget, LongSupplier clockMillis) {
        this(quotaGuard, guardrail, cache, aliasResolver, router, providers, retryBudget,
                clockMillis, null, null, null);
    }

    public GatewayPipeline(QuotaGuard quotaGuard, Guardrail guardrail, SemanticCache cache,
                           AliasResolver aliasResolver, PolicyRouter router, ProviderRegistry providers,
                           int maxAttempts, LongSupplier clockMillis, FallbackChain fallbackChain,
                           CircuitBreaker circuitBreaker, RetryBudget retryBudget) {
        this.quotaGuard = quotaGuard;
        this.guardrail = guardrail;
        this.cache = cache;
        this.aliasResolver = aliasResolver;
        this.router = router;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.fallbackChain = fallbackChain == null ? new FallbackChain(providers) : fallbackChain;
        this.circuitBreaker = circuitBreaker;
        this.retryBudget = retryBudget;
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
    }

    public RequestRecord handle(CompletionRequest request, PipelineMode mode) {
        return execute(request, mode).record();
    }

    public GatewayResult execute(CompletionRequest request, PipelineMode mode) {
        long start = clockMillis.getAsLong();
        int estimatedTokens = estimateTokens(request.prompt()) + request.maxTokens();

        QuotaOutcome quota = quotaGuard.evaluate(request, estimatedTokens);
        if (quota != QuotaOutcome.ALLOWED) {
            return rejected(request, mode, quota, elapsed(start));
        }

        if (guardrail.inspectInput(request) != GuardrailResult.PASS) {
            return new GatewayResult(guardrailBlocked(request, mode, GuardrailResult.BLOCKED_INPUT, elapsed(start)), null);
        }

        // Cache is skipped for PASSTHROUGH so it can serve as the no-cache baseline.
        if (mode != PipelineMode.PASSTHROUGH) {
            SemanticCache.Lookup lookup = cache.lookup(request);
            if (lookup.hit()) {
                return fromCache(request, mode, lookup, elapsed(start));
            }
        }

        RoutingPlan plan = aliasResolver.resolve(request.tenantId(), request.alias());
        List<ModelCandidate> ordered = router.order(plan.strategy(), plan.candidates());
        if (ordered.isEmpty()) {
            return new GatewayResult(
                    failed(request, mode, 0, "no healthy candidate for alias '" + request.alias() + "'", elapsed(start)),
                    null);
        }

        int attempts = mode == PipelineMode.ROUTED_RESILIENT ? Math.min(maxAttempts, ordered.size()) : 1;
        FallbackResult dispatched = fallbackChain.dispatch(ordered, request, attempts, circuitBreaker, retryBudget);
        if (!dispatched.success()) {
            return new GatewayResult(
                    failed(request, mode, dispatched.fallbacksUsed(),
                            "all candidates failed: " + dispatched.lastErrorType(), elapsed(start)),
                    null);
        }

        ModelCandidate candidate = dispatched.chosen();
        CompletionResponse response = dispatched.response();
        GuardrailResult out = guardrail.inspectOutput(response);
        if (out != GuardrailResult.PASS) {
            return new GatewayResult(new RequestRecord(request.tenantId(), request.alias(), mode,
                    candidate.provider(), candidate.model(),
                    response.usage().promptTokens(), response.usage().completionTokens(),
                    elapsed(start), cost(candidate, response.usage().totalTokens()),
                    CacheType.NONE, dispatched.fallbacksUsed(), GuardrailResult.BLOCKED_OUTPUT,
                    QuotaOutcome.ALLOWED, "output guardrail blocked response"), null);
        }

        if (mode != PipelineMode.PASSTHROUGH) {
            cache.store(request, response);
        }
        RequestRecord record = new RequestRecord(request.tenantId(), request.alias(), mode,
                candidate.provider(), candidate.model(),
                response.usage().promptTokens(), response.usage().completionTokens(),
                elapsed(start), cost(candidate, response.usage().totalTokens()),
                CacheType.NONE, dispatched.fallbacksUsed(), GuardrailResult.PASS,
                QuotaOutcome.ALLOWED,
                dispatched.fallbacksUsed() == 0 ? "ok" : "ok after " + dispatched.fallbacksUsed() + " fallback(s)");
        return new GatewayResult(record, response);
    }

    private GatewayResult fromCache(CompletionRequest request, PipelineMode mode,
                                    SemanticCache.Lookup lookup, long latencyMs) {
        CompletionResponse response = lookup.response();
        RequestRecord record = new RequestRecord(request.tenantId(), request.alias(), mode,
                "cache", response.model(),
                response.usage().promptTokens(), response.usage().completionTokens(),
                latencyMs, 0L, lookup.type(), 0,
                GuardrailResult.PASS, QuotaOutcome.ALLOWED, "served from " + lookup.type() + " cache");
        return new GatewayResult(record, response);
    }

    private GatewayResult rejected(CompletionRequest request, PipelineMode mode, QuotaOutcome outcome, long latencyMs) {
        return new GatewayResult(new RequestRecord(request.tenantId(), request.alias(), mode, null, null,
                0, 0, latencyMs, 0L, CacheType.NONE, 0, GuardrailResult.PASS, outcome,
                "rejected by quota: " + outcome), null);
    }

    private RequestRecord guardrailBlocked(CompletionRequest request, PipelineMode mode, GuardrailResult result, long latencyMs) {
        return new RequestRecord(request.tenantId(), request.alias(), mode, null, null,
                0, 0, latencyMs, 0L, CacheType.NONE, 0, result, QuotaOutcome.ALLOWED, "blocked by input guardrail");
    }

    private RequestRecord failed(CompletionRequest request, PipelineMode mode, int fallbackCount, String message, long latencyMs) {
        return new RequestRecord(request.tenantId(), request.alias(), mode, null, null,
                0, 0, latencyMs, 0L, CacheType.NONE, fallbackCount, GuardrailResult.PASS, QuotaOutcome.ALLOWED, message);
    }

    private long elapsed(long start) {
        return Math.max(0, clockMillis.getAsLong() - start);
    }

    /** Cost in the same micro-unit as {@link ModelCandidate#costPerKTokens()}. */
    static long cost(ModelCandidate candidate, int totalTokens) {
        return candidate.costPerKTokens() * totalTokens / 1000;
    }

    /** Deterministic token estimate: roughly one token per four characters, min 1. */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
