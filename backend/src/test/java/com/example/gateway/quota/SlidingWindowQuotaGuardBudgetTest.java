package com.example.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.example.gateway.provider.CompletionRequest;

/**
 * Unit tests for the budget stage added to {@link SlidingWindowQuotaGuard} in B2, covering the
 * rate-then-budget ordering and the token/cost reservation boundary. The rate limit is left
 * unconfigured (so budget is the binding stage) except where ordering is the point. Real Redis /
 * PostgreSQL reconciliation is the E1 slice; here the in-memory store proves the decision.
 */
class SlidingWindowQuotaGuardBudgetTest {

    private static final String PERIOD = "2026-06";

    private final Map<String, RateLimitPolicy> ratePolicies = new HashMap<>();
    private final Map<String, BudgetPolicy> budgetPolicies = new HashMap<>();
    private final RateLimitPolicyProvider rateProvider =
            tenantId -> Optional.ofNullable(ratePolicies.get(tenantId));
    private final BudgetPolicyProvider budgetProvider =
            tenantId -> Optional.ofNullable(budgetPolicies.get(tenantId));

    private CompletionRequest request(String tenantId) {
        return new CompletionRequest(tenantId, "gpt-4o", "hello", 16, false);
    }

    private SlidingWindowQuotaGuard guard(CostEstimator costEstimator) {
        return new SlidingWindowQuotaGuard(
                new InMemoryRateLimitStore(), rateProvider,
                new InMemoryBudgetStore(), budgetProvider,
                costEstimator, fixedClock(1_000_000L));
    }

    @Test
    void allowsReservationsUpToTheTokenBudgetThenRejects() {
        // Cost is non-binding (rate of 0), so the token dimension decides.
        budgetPolicies.put("tenant-1", new BudgetPolicy(PERIOD, 100, Long.MAX_VALUE));
        SlidingWindowQuotaGuard guard = guard(new FlatRateCostEstimator(0));

        // Four reservations of 25 tokens reach exactly the limit of 100.
        for (int i = 0; i < 4; i++) {
            assertThat(guard.evaluate(request("tenant-1"), 25)).isEqualTo(QuotaOutcome.ALLOWED);
        }
        // The next token would cross the budget.
        assertThat(guard.evaluate(request("tenant-1"), 1)).isEqualTo(QuotaOutcome.BUDGET_EXCEEDED);
    }

    @Test
    void costLimitCanBindBeforeTheTokenLimit() {
        // Token limit is generous; cost (2 micro-units/token) is what runs out first.
        budgetPolicies.put("tenant-1", new BudgetPolicy(PERIOD, 1_000_000, 100));
        SlidingWindowQuotaGuard guard = guard(new FlatRateCostEstimator(2));

        // 25 tokens -> 50 cost, twice -> 100 cost, exactly the cost limit.
        assertThat(guard.evaluate(request("tenant-1"), 25)).isEqualTo(QuotaOutcome.ALLOWED);
        assertThat(guard.evaluate(request("tenant-1"), 25)).isEqualTo(QuotaOutcome.ALLOWED);
        // Another token costs 2 more -> over the cost limit, though tokens are nowhere near theirs.
        assertThat(guard.evaluate(request("tenant-1"), 1)).isEqualTo(QuotaOutcome.BUDGET_EXCEEDED);
    }

    @Test
    void rateLimitIsDecidedBeforeAndWithoutTouchingTheBudget() {
        ratePolicies.put("tenant-1", new RateLimitPolicy(60, 1));
        budgetPolicies.put("tenant-1", new BudgetPolicy(PERIOD, 1_000, Long.MAX_VALUE));
        InMemoryBudgetStore budgetStore = new InMemoryBudgetStore();
        SlidingWindowQuotaGuard guard = new SlidingWindowQuotaGuard(
                new InMemoryRateLimitStore(), rateProvider,
                budgetStore, budgetProvider,
                new FlatRateCostEstimator(0), fixedClock(1_000_000L));

        // First request clears the rate limit and reserves 10 tokens of budget.
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        // Second is rate limited and must short-circuit before the budget stage (no double count).
        assertThat(guard.evaluate(request("tenant-1"), 10)).isEqualTo(QuotaOutcome.RATE_LIMITED);

        // Only the first, admitted request drew down the budget.
        assertThat(budgetStore.consumedTokens("budget:tenant-1:" + PERIOD)).isEqualTo(10);
    }

    @Test
    void tenantWithoutBudgetIsNeverBudgetLimited() {
        SlidingWindowQuotaGuard guard = guard(new FlatRateCostEstimator(1));

        for (int i = 0; i < 100; i++) {
            assertThat(guard.evaluate(request("no-budget"), 10_000)).isEqualTo(QuotaOutcome.ALLOWED);
        }
    }

    @Test
    void budgetsAreIsolatedPerTenant() {
        budgetPolicies.put("tenant-a", new BudgetPolicy(PERIOD, 10, Long.MAX_VALUE));
        budgetPolicies.put("tenant-b", new BudgetPolicy(PERIOD, 10, Long.MAX_VALUE));
        SlidingWindowQuotaGuard guard = guard(new FlatRateCostEstimator(0));

        assertThat(guard.evaluate(request("tenant-a"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
        assertThat(guard.evaluate(request("tenant-a"), 1)).isEqualTo(QuotaOutcome.BUDGET_EXCEEDED);
        // tenant-b has its own budget and is unaffected by tenant-a's exhaustion.
        assertThat(guard.evaluate(request("tenant-b"), 10)).isEqualTo(QuotaOutcome.ALLOWED);
    }

    private static Clock fixedClock(long epochMillis) {
        return Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }
}
