package com.example.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.FakeLlmProvider;
import com.example.gateway.provider.LlmProvider;
import com.example.gateway.provider.ProviderRegistry;
import com.example.gateway.provider.Usage;
import com.example.gateway.router.ModelCandidate;
import com.example.gateway.router.PolicyRouter;
import com.example.gateway.router.RoutingStrategy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * M4-1: the fallback chain as a first-class component. Each test drives a real
 * {@link FallbackChain} over an ordered candidate list (produced by the real
 * {@link PolicyRouter#order}) with controllable provider fakes, and asserts both the success/failure
 * result and the per-attempt {@link FallbackEvent} projection used for {@code fallback_events}.
 */
class FallbackChainTest {

    private static final CompletionRequest REQUEST =
            new CompletionRequest("tenant-1", "gpt-4o", "hello world", 64, false);

    /** Registry backed by a provider-id -> provider map; a missing id throws (registry contract). */
    private static ProviderRegistry registry(Map<String, LlmProvider> byId) {
        return id -> {
            LlmProvider p = byId.get(id);
            if (p == null) {
                throw new IllegalArgumentException("no provider registered for '" + id + "'");
            }
            return p;
        };
    }

    private static ModelCandidate candidate(String provider, String model) {
        return new ModelCandidate(provider, model, 1000L, 10L, true);
    }

    /** Ordered chain straight from the real router (FIXED keeps declared tiebreak order deterministic). */
    private static List<ModelCandidate> ordered(ModelCandidate... candidates) {
        return new PolicyRouter().order(RoutingStrategy.FIXED, List.of(candidates));
    }

    // ---- case: primary fails, secondary succeeds ---------------------------

    @Test
    void primaryFailsSecondarySucceeds() {
        FakeLlmProvider primary = new FakeLlmProvider("p-a");
        primary.setHealthy(false); // complete() throws IllegalStateException
        FakeLlmProvider secondary = new FakeLlmProvider("p-b");

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", primary, "p-b", secondary)));
        // Force a specific order: p-a primary, p-b fallback, via FIXED tiebreak (p-a < p-b).
        FallbackResult result = chain.dispatch(ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.fallbacksUsed()).isEqualTo(1);
        assertThat(result.chosen().provider()).isEqualTo("p-b");
        assertThat(result.response().content()).contains("p-b:m-b");

        assertThat(result.events()).hasSize(2);
        FallbackEvent first = result.events().get(0);
        assertThat(first.attemptNo()).isZero();
        assertThat(first.provider()).isEqualTo("p-a");
        assertThat(first.model()).isEqualTo("m-a");
        assertThat(first.outcome()).isEqualTo(FallbackOutcome.FAILED);
        assertThat(first.errorType()).contains("unhealthy");

        FallbackEvent second = result.events().get(1);
        assertThat(second.attemptNo()).isEqualTo(1);
        assertThat(second.provider()).isEqualTo("p-b");
        assertThat(second.outcome()).isEqualTo(FallbackOutcome.SUCCESS);
        assertThat(second.errorType()).isNull();
    }

    // ---- case: all candidates fail -----------------------------------------

    @Test
    void allCandidatesFailPreservesLastError() {
        FakeLlmProvider a = new FakeLlmProvider("p-a");
        a.setHealthy(false);
        FakeLlmProvider b = new FakeLlmProvider("p-b");
        b.setHealthy(false);

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", a, "p-b", b)));
        FallbackResult result = chain.dispatch(ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST);

        assertThat(result.success()).isFalse();
        assertThat(result.response()).isNull();
        assertThat(result.chosen()).isNull();
        assertThat(result.fallbacksUsed()).isEqualTo(1);
        assertThat(result.lastErrorType()).contains("p-b").contains("unhealthy");

        assertThat(result.events()).hasSize(2);
        assertThat(result.events()).allSatisfy(e -> assertThat(e.outcome()).isEqualTo(FallbackOutcome.FAILED));
        assertThat(result.events().get(0).attemptNo()).isZero();
        assertThat(result.events().get(1).attemptNo()).isEqualTo(1);
    }

    // ---- case: primary succeeds immediately --------------------------------

    @Test
    void primarySucceedsNoFallback() {
        FakeLlmProvider primary = new FakeLlmProvider("p-a");
        FakeLlmProvider secondary = new FakeLlmProvider("p-b");

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", primary, "p-b", secondary)));
        FallbackResult result = chain.dispatch(ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.fallbacksUsed()).isZero();
        assertThat(result.chosen().provider()).isEqualTo("p-a");
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).outcome()).isEqualTo(FallbackOutcome.SUCCESS);
        assertThat(result.events().get(0).attemptNo()).isZero();
        assertThat(result.events().get(0).errorType()).isNull();
    }

    // ---- case: single candidate, no fallback possible, fails ---------------

    @Test
    void singleCandidateFailureHasOneFailedEvent() {
        FakeLlmProvider only = new FakeLlmProvider("p-a");
        only.setHealthy(false);

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", only)));
        FallbackResult result = chain.dispatch(ordered(candidate("p-a", "m-a")), REQUEST);

        assertThat(result.success()).isFalse();
        assertThat(result.fallbacksUsed()).isZero();
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).outcome()).isEqualTo(FallbackOutcome.FAILED);
        assertThat(result.events().get(0).provider()).isEqualTo("p-a");
    }

    // ---- case: maxAttempts (retry budget) caps the chain -------------------

    @Test
    void maxAttemptsCapsCandidatesTried() {
        FakeLlmProvider a = new FakeLlmProvider("p-a");
        a.setHealthy(false);
        FakeLlmProvider b = new FakeLlmProvider("p-b"); // healthy, but never reached under cap=1

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", a, "p-b", b)));
        FallbackResult result =
                chain.dispatch(ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST, 1);

        assertThat(result.success()).isFalse();
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).provider()).isEqualTo("p-a");
    }

    // ---- case: registry miss is treated as a failed attempt ----------------

    @Test
    void registryMissCountsAsFailedAttempt() {
        FakeLlmProvider b = new FakeLlmProvider("p-b");
        // p-a candidate present in chain but absent from registry -> get() throws.
        FallbackChain chain = new FallbackChain(registry(Map.of("p-b", b)));
        FallbackResult result = chain.dispatch(ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.chosen().provider()).isEqualTo("p-b");
        assertThat(result.events().get(0).outcome()).isEqualTo(FallbackOutcome.FAILED);
        assertThat(result.events().get(0).errorType()).contains("no provider registered");
    }

    @Test
    void emptyOrderedChainRejected() {
        FallbackChain chain = new FallbackChain(id -> new FakeLlmProvider(id));
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> chain.dispatch(List.of(), REQUEST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- a custom provider proving the usage/response is carried through ----

    // ---- M4-2: circuit breaker integration ---------------------------------

    /** Force a provider's breaker OPEN by recording threshold failures (threshold=1 here). */
    private static com.example.gateway.resilience.CircuitBreaker openBreaker(String providerId) {
        com.example.gateway.resilience.CircuitBreaker cb =
                new com.example.gateway.resilience.CircuitBreaker(1, 10_000L, () -> 0L);
        cb.recordFailure(providerId); // threshold 1 → OPEN immediately
        return cb;
    }

    @Test
    void openPrimarySkippedFallsBackToSecondary() {
        // Both providers are HEALTHY; only the breaker (OPEN on p-a) diverts traffic.
        FakeLlmProvider primary = new FakeLlmProvider("p-a");
        FakeLlmProvider secondary = new FakeLlmProvider("p-b");
        com.example.gateway.resilience.CircuitBreaker cb = openBreaker("p-a");

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", primary, "p-b", secondary)));
        FallbackResult result = chain.dispatch(
                ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST, 2, cb);

        assertThat(result.success()).isTrue();
        assertThat(result.chosen().provider()).isEqualTo("p-b");
        assertThat(result.fallbacksUsed()).isEqualTo(1);

        // p-a was skipped (not attempted) and recorded as a circuit_open FAILED event.
        assertThat(result.events()).hasSize(2);
        FallbackEvent skipped = result.events().get(0);
        assertThat(skipped.provider()).isEqualTo("p-a");
        assertThat(skipped.outcome()).isEqualTo(FallbackOutcome.FAILED);
        assertThat(skipped.errorType()).isEqualTo(FallbackChain.CIRCUIT_OPEN);
        assertThat(result.events().get(1).provider()).isEqualTo("p-b");
        assertThat(result.events().get(1).outcome()).isEqualTo(FallbackOutcome.SUCCESS);
    }

    @Test
    void attemptedFailureIsRecordedToBreaker() {
        // p-a healthy=false so its complete() throws; breaker starts CLOSED with threshold 1.
        FakeLlmProvider a = new FakeLlmProvider("p-a");
        a.setHealthy(false);
        FakeLlmProvider b = new FakeLlmProvider("p-b");
        com.example.gateway.resilience.CircuitBreaker cb =
                new com.example.gateway.resilience.CircuitBreaker(1, 10_000L, () -> 0L);

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", a, "p-b", b)));
        FallbackResult result = chain.dispatch(
                ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST, 2, cb);

        assertThat(result.success()).isTrue();
        assertThat(result.chosen().provider()).isEqualTo("p-b");

        // The real attempt on p-a failed → breaker recorded it and (threshold 1) tripped OPEN.
        assertThat(cb.state("p-a")).isEqualTo(com.example.gateway.resilience.CircuitState.OPEN);
        // p-b succeeded → its breaker stays CLOSED.
        assertThat(cb.state("p-b")).isEqualTo(com.example.gateway.resilience.CircuitState.CLOSED);

        // p-a's event is its real failure (unhealthy), not a circuit_open skip.
        assertThat(result.events().get(0).errorType()).contains("unhealthy");
    }

    @Test
    void allCircuitsOpenFailsWithCircuitOpenEvents() {
        FakeLlmProvider a = new FakeLlmProvider("p-a");
        FakeLlmProvider b = new FakeLlmProvider("p-b");
        com.example.gateway.resilience.CircuitBreaker cb =
                new com.example.gateway.resilience.CircuitBreaker(1, 10_000L, () -> 0L);
        cb.recordFailure("p-a");
        cb.recordFailure("p-b"); // both OPEN

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", a, "p-b", b)));
        FallbackResult result = chain.dispatch(
                ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST, 2, cb);

        assertThat(result.success()).isFalse();
        assertThat(result.lastErrorType()).isEqualTo(FallbackChain.CIRCUIT_OPEN);
        assertThat(result.events()).hasSize(2);
        assertThat(result.events()).allSatisfy(e -> {
            assertThat(e.outcome()).isEqualTo(FallbackOutcome.FAILED);
            assertThat(e.errorType()).isEqualTo(FallbackChain.CIRCUIT_OPEN);
        });
    }

    @Test
    void nullBreakerReproducesNoBreakerBehavior() {
        FakeLlmProvider primary = new FakeLlmProvider("p-a");
        primary.setHealthy(false);
        FakeLlmProvider secondary = new FakeLlmProvider("p-b");

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", primary, "p-b", secondary)));
        FallbackResult withNull = chain.dispatch(
                ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST, 2, null);

        // Identical to the no-breaker overload: p-a fails, p-b succeeds, fallbacksUsed=1.
        assertThat(withNull.success()).isTrue();
        assertThat(withNull.chosen().provider()).isEqualTo("p-b");
        assertThat(withNull.fallbacksUsed()).isEqualTo(1);
        assertThat(withNull.events().get(0).errorType()).contains("unhealthy");
    }

    // ---- M4-3: retry budget integration ------------------------------------

    /** A provider that counts how many times {@code complete} is invoked; optionally always fails. */
    private static final class CountingProvider implements LlmProvider {
        final String name;
        final boolean fail;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        CountingProvider(String name, boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override public String name() { return name; }
        @Override public boolean healthy() { return !fail; }
        @Override public CompletionResponse complete(String model, CompletionRequest request) {
            calls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("provider '" + name + "' is unhealthy");
            }
            return new CompletionResponse("[" + name + ":" + model + "]", model, new Usage(1, 1));
        }
    }

    @Test
    void budgetSufficientFallsBackAndSucceeds() {
        // Budget with room: behaves exactly like the no-budget path (primary fails, secondary wins).
        FakeLlmProvider primary = new FakeLlmProvider("p-a");
        primary.setHealthy(false);
        FakeLlmProvider secondary = new FakeLlmProvider("p-b");
        RetryBudget budget = new RetryBudget(5, 0, () -> 0L);

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", primary, "p-b", secondary)));
        FallbackResult result = chain.dispatch(
                ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST, 2, null, budget);

        assertThat(result.success()).isTrue();
        assertThat(result.chosen().provider()).isEqualTo("p-b");
        assertThat(result.fallbacksUsed()).isEqualTo(1);
        // One fallback spent exactly one token.
        assertThat(budget.available()).isEqualTo(4.0);
    }

    @Test
    void exhaustedBudgetBlocksFallbackWithoutCallingProvider() {
        CountingProvider a = new CountingProvider("p-a", true);  // primary fails
        CountingProvider b = new CountingProvider("p-b", false); // healthy, but must never be called

        // Empty budget: capacity 1 already drained so the first fallback is blocked.
        RetryBudget budget = new RetryBudget(1, 0, () -> 0L);
        assertThat(budget.tryRetry()).isTrue(); // drain to 0

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", a, "p-b", b)));
        FallbackResult result = chain.dispatch(
                ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST, 2, null, budget);

        assertThat(result.success()).isFalse();
        assertThat(result.lastErrorType()).isEqualTo(FallbackChain.RETRY_BUDGET_EXHAUSTED);

        // Primary was attempted once; the fallback candidate was NEVER called (budget short-circuit).
        assertThat(a.calls.get()).isEqualTo(1);
        assertThat(b.calls.get()).isZero();

        // Events: primary's real failure, then the budget-exhausted marker on the blocked candidate.
        assertThat(result.events()).hasSize(2);
        assertThat(result.events().get(0).provider()).isEqualTo("p-a");
        assertThat(result.events().get(0).errorType()).contains("unhealthy");
        FallbackEvent blocked = result.events().get(1);
        assertThat(blocked.provider()).isEqualTo("p-b");
        assertThat(blocked.outcome()).isEqualTo(FallbackOutcome.FAILED);
        assertThat(blocked.errorType()).isEqualTo(FallbackChain.RETRY_BUDGET_EXHAUSTED);
    }

    @Test
    void primaryDoesNotConsumeBudget() {
        // Primary succeeds: budget must be untouched (only fallbacks spend tokens).
        FakeLlmProvider primary = new FakeLlmProvider("p-a");
        RetryBudget budget = new RetryBudget(3, 0, () -> 0L);

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", primary)));
        FallbackResult ok = chain.dispatch(ordered(candidate("p-a", "m-a")), REQUEST, 2, null, budget);
        assertThat(ok.success()).isTrue();
        assertThat(budget.available()).isEqualTo(3.0);

        // Primary fails with no fallback candidate available: still spends nothing (single candidate).
        FakeLlmProvider lonely = new FakeLlmProvider("p-x");
        lonely.setHealthy(false);
        FallbackChain solo = new FallbackChain(registry(Map.of("p-x", lonely)));
        FallbackResult fail = solo.dispatch(ordered(candidate("p-x", "m-x")), REQUEST, 2, null, budget);
        assertThat(fail.success()).isFalse();
        assertThat(budget.available()).isEqualTo(3.0);
    }

    @Test
    void refillAfterExhaustionReadmitsFallback() {
        FakeLlmProvider primary = new FakeLlmProvider("p-a");
        primary.setHealthy(false);
        FakeLlmProvider secondary = new FakeLlmProvider("p-b");
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0);
        RetryBudget budget = new RetryBudget(1, 1.0, clock::get); // 1 token, 1/sec refill

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", primary, "p-b", secondary)));
        var chainArgs = ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b"));

        // First request: token available -> fallback to p-b succeeds, budget drained.
        assertThat(chain.dispatch(chainArgs, REQUEST, 2, null, budget).success()).isTrue();

        // Second request immediately: budget empty -> fallback blocked.
        FallbackResult blocked = chain.dispatch(chainArgs, REQUEST, 2, null, budget);
        assertThat(blocked.success()).isFalse();
        assertThat(blocked.lastErrorType()).isEqualTo(FallbackChain.RETRY_BUDGET_EXHAUSTED);

        // Advance the clock 1s -> bucket refills 1 token -> fallback admitted again.
        clock.addAndGet(1000);
        assertThat(chain.dispatch(chainArgs, REQUEST, 2, null, budget).success()).isTrue();
    }

    @Test
    void circuitBreakerAndBudgetComposeCircuitSkipDoesNotSpendBudget() {
        // p-a circuit OPEN (skipped, no token), p-b real fallback (spends one token) succeeds.
        FakeLlmProvider primary = new FakeLlmProvider("p-a");
        FakeLlmProvider secondary = new FakeLlmProvider("p-b");
        com.example.gateway.resilience.CircuitBreaker cb = openBreaker("p-a");
        RetryBudget budget = new RetryBudget(5, 0, () -> 0L);

        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", primary, "p-b", secondary)));
        FallbackResult result = chain.dispatch(
                ordered(candidate("p-a", "m-a"), candidate("p-b", "m-b")), REQUEST, 2, cb, budget);

        assertThat(result.success()).isTrue();
        assertThat(result.chosen().provider()).isEqualTo("p-b");
        // p-a was a circuit_open skip (attempt 0 = primary, so no budget gate there either);
        // p-b is a real fallback -> exactly one token spent.
        assertThat(budget.available()).isEqualTo(4.0);
        assertThat(result.events().get(0).errorType()).isEqualTo(FallbackChain.CIRCUIT_OPEN);
        assertThat(result.events().get(1).outcome()).isEqualTo(FallbackOutcome.SUCCESS);
    }

    @Test
    void successResponseCarriesProviderUsage() {
        LlmProvider rich = new LlmProvider() {
            @Override public String name() { return "p-a"; }
            @Override public boolean healthy() { return true; }
            @Override public CompletionResponse complete(String model, CompletionRequest request) {
                return new CompletionResponse("answer", model, new Usage(7, 11));
            }
        };
        FallbackChain chain = new FallbackChain(registry(Map.of("p-a", rich)));
        FallbackResult result = chain.dispatch(ordered(candidate("p-a", "m-a")), REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.response().usage().promptTokens()).isEqualTo(7);
        assertThat(result.response().usage().completionTokens()).isEqualTo(11);
    }
}
