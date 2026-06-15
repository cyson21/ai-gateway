package com.example.gateway.quota;

import java.time.Clock;
import java.util.Optional;

import com.example.gateway.provider.CompletionRequest;

/**
 * {@link QuotaGuard} that enforces, in order, the per-tenant sliding-window rate limit and then the
 * per-tenant/per-period token/cost budget. For each request it first atomically reserves a slot in
 * the {@link RateLimitStore} (B1); a hit over the limit short-circuits to
 * {@link QuotaOutcome#RATE_LIMITED} without touching the budget. Once admitted by the rate limit,
 * it atomically reserves {@code estimatedTokens} (and the {@link CostEstimator}-projected cost) in
 * the {@link BudgetStore} (B2); a draw over either the token or cost limit yields
 * {@link QuotaOutcome#BUDGET_EXCEEDED}. Tenants without a configured policy are not limited by the
 * corresponding stage.
 *
 * <p>This guard only <em>reserves</em>; refund-on-failure and reconcile-to-actual
 * ({@link BudgetStore#release}/{@link BudgetStore#commit}) belong to the pipeline that wraps it,
 * wired in a later slice. Real Redis/PostgreSQL backing and distributed proof are the E1 slice.
 */
public final class SlidingWindowQuotaGuard implements QuotaGuard {

    private final RateLimitStore rateStore;
    private final RateLimitPolicyProvider ratePolicies;
    private final BudgetStore budgetStore;
    private final BudgetPolicyProvider budgetPolicies;
    private final CostEstimator costEstimator;
    private final Clock clock;

    /**
     * Rate-limit-only guard (B1 behavior): no tenant has a budget, so the budget stage is a no-op
     * and every request that clears the rate limit is {@link QuotaOutcome#ALLOWED}.
     */
    public SlidingWindowQuotaGuard(RateLimitStore store, RateLimitPolicyProvider policies, Clock clock) {
        this(store, policies, new InMemoryBudgetStore(), tenantId -> Optional.empty(),
                new FlatRateCostEstimator(0), clock);
    }

    public SlidingWindowQuotaGuard(
            RateLimitStore rateStore,
            RateLimitPolicyProvider ratePolicies,
            BudgetStore budgetStore,
            BudgetPolicyProvider budgetPolicies,
            CostEstimator costEstimator,
            Clock clock) {
        this.rateStore = rateStore;
        this.ratePolicies = ratePolicies;
        this.budgetStore = budgetStore;
        this.budgetPolicies = budgetPolicies;
        this.costEstimator = costEstimator;
        this.clock = clock;
    }

    @Override
    public QuotaOutcome evaluate(CompletionRequest request, int estimatedTokens) {
        Optional<RateLimitPolicy> ratePolicy = ratePolicies.forTenant(request.tenantId());
        if (ratePolicy.isPresent()) {
            RateLimitPolicy limit = ratePolicy.get();
            String key = rateLimitKey(request.tenantId(), limit.windowSeconds());
            boolean admitted = rateStore.tryAcquire(key, clock.millis(), limit.windowMillis(), limit.maxRequests());
            if (!admitted) {
                return QuotaOutcome.RATE_LIMITED;
            }
        }

        Optional<BudgetPolicy> budgetPolicy = budgetPolicies.forTenant(request.tenantId());
        if (budgetPolicy.isPresent()) {
            BudgetPolicy budget = budgetPolicy.get();
            String key = budgetKey(request.tenantId(), budget.period());
            long cost = costEstimator.estimateCost(request, estimatedTokens);
            boolean reserved = budgetStore.reserve(
                    key, estimatedTokens, cost, budget.tokenLimit(), budget.costLimit());
            if (!reserved) {
                return QuotaOutcome.BUDGET_EXCEEDED;
            }
        }

        return QuotaOutcome.ALLOWED;
    }

    private static String rateLimitKey(String tenantId, int windowSeconds) {
        return "ratelimit:" + tenantId + ":" + windowSeconds;
    }

    private static String budgetKey(String tenantId, String period) {
        return "budget:" + tenantId + ":" + period;
    }
}
