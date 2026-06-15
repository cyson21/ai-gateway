package com.example.gateway.config;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.cache.DeterministicEmbeddingModel;
import com.example.gateway.cache.ExactCache;
import com.example.gateway.cache.InMemoryCacheStore;
import com.example.gateway.cache.InMemorySemanticCacheStore;
import com.example.gateway.cache.PgVectorSemanticCache;
import com.example.gateway.cache.SemanticCache;
import com.example.gateway.cache.TwoStageCache;
import com.example.gateway.guardrail.RuleBasedGuardrail;
import com.example.gateway.observability.InMemoryRequestLogStore;
import com.example.gateway.observability.RequestLogStore;
import com.example.gateway.provider.FakeLlmProvider;
import com.example.gateway.provider.LlmProvider;
import com.example.gateway.provider.ProviderRegistry;
import com.example.gateway.quota.BudgetPolicy;
import com.example.gateway.quota.FlatRateCostEstimator;
import com.example.gateway.quota.InMemoryBudgetStore;
import com.example.gateway.quota.InMemoryRateLimitStore;
import com.example.gateway.quota.RateLimitPolicy;
import com.example.gateway.quota.SlidingWindowQuotaGuard;
import com.example.gateway.resilience.CircuitBreaker;
import com.example.gateway.resilience.FallbackChain;
import com.example.gateway.resilience.RetryBudget;
import com.example.gateway.router.AliasResolver;
import com.example.gateway.router.ModelCandidate;
import com.example.gateway.router.PolicyRouter;
import com.example.gateway.router.RoutingPlan;
import com.example.gateway.router.RoutingStrategy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the local demo runtime with the real deterministic components: in-memory quota and budget
 * stores, rule-based guardrails, two-stage cache, policy routing, fallback chain, circuit breaker,
 * retry budget, and fake providers. It stays network-free by default while exercising the same
 * gateway path the persisted Redis/PostgreSQL adapters implement.
 */
@Configuration
public class GatewayPipelineConfig {

    private static final String PRIMARY = "fake-primary";
    private static final String FALLBACK = "fake-fallback";

    @Bean
    public GatewayPipeline gatewayPipeline() {
        SlidingWindowQuotaGuard quotaGuard = new SlidingWindowQuotaGuard(
                new InMemoryRateLimitStore(),
                tenantId -> Optional.of(new RateLimitPolicy(60, 120)),
                new InMemoryBudgetStore(),
                tenantId -> Optional.of(new BudgetPolicy("demo", 1_000_000L, 1_000_000L)),
                new FlatRateCostEstimator(1L),
                Clock.systemUTC());

        RuleBasedGuardrail guardrail = RuleBasedGuardrail.defaults(
                List.of("forbidden", "blocked", "credential leak"), 8_000);

        SemanticCache cache = new TwoStageCache(
                new ExactCache(new InMemoryCacheStore()),
                new PgVectorSemanticCache(
                        new DeterministicEmbeddingModel(),
                        new InMemorySemanticCacheStore(),
                        0.82));

        Map<String, LlmProvider> providerMap = Map.of(
                PRIMARY, new FakeLlmProvider(PRIMARY),
                FALLBACK, new FakeLlmProvider(FALLBACK));

        AliasResolver aliasResolver = (tenantId, alias) -> new RoutingPlan(
                strategyFor(alias),
                candidatesFor(alias));
        PolicyRouter router = new PolicyRouter();
        ProviderRegistry providers = providerId -> {
            LlmProvider provider = providerMap.get(providerId);
            if (provider != null) {
                return provider;
            }
            throw new IllegalArgumentException("no provider registered for id '" + providerId + "'");
        };

        CircuitBreaker breaker = new CircuitBreaker(3, 30_000L, System::currentTimeMillis);
        RetryBudget retryBudget = new RetryBudget(50, 5, System::currentTimeMillis);
        return new GatewayPipeline(quotaGuard, guardrail, cache, aliasResolver, router, providers, 3,
                null, new FallbackChain(providers), breaker, retryBudget);
    }

    @Bean
    public RequestLogStore requestLogStore() {
        return new InMemoryRequestLogStore(System::currentTimeMillis);
    }

    private static RoutingStrategy strategyFor(String alias) {
        if ("cheap".equals(alias)) {
            return RoutingStrategy.LEAST_COST;
        }
        if ("fast".equals(alias)) {
            return RoutingStrategy.LEAST_LATENCY;
        }
        return RoutingStrategy.FIXED;
    }

    private static List<ModelCandidate> candidatesFor(String alias) {
        String model = alias == null || alias.isBlank() ? "gpt-4o" : alias;
        if ("cheap".equals(alias)) {
            return List.of(
                    new ModelCandidate(FALLBACK, "economy-model", 100L, 35L, true),
                    new ModelCandidate(PRIMARY, "premium-model", 900L, 12L, true));
        }
        if ("fast".equals(alias)) {
            return List.of(
                    new ModelCandidate(PRIMARY, "fast-model", 800L, 8L, true),
                    new ModelCandidate(FALLBACK, "steady-model", 250L, 30L, true));
        }
        if ("resilient".equals(alias)) {
            return List.of(
                    new ModelCandidate(PRIMARY, "resilient-primary", 500L, 12L, true),
                    new ModelCandidate(FALLBACK, "resilient-fallback", 550L, 18L, true));
        }
        return List.of(new ModelCandidate(PRIMARY, model, 500L, 12L, true));
    }
}
