package com.example.gateway.resilience;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.LlmProvider;
import com.example.gateway.provider.ProviderRegistry;
import com.example.gateway.router.ModelCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * First-class model of the gateway's fallback chain. Given an <b>ordered</b> candidate list
 * (the head is the primary choice, the tail is the ordered fallback chain — exactly the shape
 * {@link com.example.gateway.router.PolicyRouter#order} produces) and a {@link ProviderRegistry},
 * it tries each candidate in turn:
 *
 * <ul>
 *   <li>resolve the candidate's provider and call {@link LlmProvider#complete};</li>
 *   <li>on success: stop, emit a {@link FallbackOutcome#SUCCESS} event for this attempt, and return;</li>
 *   <li>on a {@link RuntimeException} (provider transport error, unhealthy, registry miss): emit a
 *       {@link FallbackOutcome#FAILED} event with a deterministic {@code error_type} label and fall
 *       back to the next candidate;</li>
 *   <li>if every candidate fails: return a failure result whose events are all {@code FAILED}.</li>
 * </ul>
 *
 * <p>This is the explicit extraction of the loop previously inlined in {@code GatewayPipeline}'s
 * resilient path. Wiring the pipeline to delegate here (and persisting the events with the
 * {@code request_id} FK) is a later slice; this slice ships the component, its event projection,
 * and the {@link FallbackEventStore} port so they can be verified in isolation.
 *
 * <p>Fully deterministic: no clock, no network, no randomness. Error labels are derived from the
 * thrown exception only.
 */
public final class FallbackChain {

    /** Deterministic {@code error_type} label stamped on a candidate skipped for an OPEN circuit. */
    public static final String CIRCUIT_OPEN = "circuit_open";

    /** Deterministic {@code error_type} label stamped when the shared retry budget blocks a fallback. */
    public static final String RETRY_BUDGET_EXHAUSTED = RetryBudget.EXHAUSTED;

    private final ProviderRegistry providers;

    public FallbackChain(ProviderRegistry providers) {
        if (providers == null) {
            throw new IllegalArgumentException("providers is required");
        }
        this.providers = providers;
    }

    /**
     * Drive the ordered chain with no attempt cap (try every candidate until one succeeds).
     */
    public FallbackResult dispatch(List<ModelCandidate> ordered, CompletionRequest request) {
        return dispatch(ordered, request, ordered == null ? 0 : ordered.size());
    }

    /**
     * Drive the ordered chain, attempting at most {@code maxAttempts} candidates (the retry-budget
     * bound). The effective cap is {@code min(maxAttempts, ordered.size())}.
     *
     * @param ordered     ordered candidates; head = primary, tail = fallback chain (must be non-empty)
     * @param request     the completion request to run against each candidate's model
     * @param maxAttempts upper bound on candidates to try (e.g. the retry budget)
     * @return a {@link FallbackResult} with the success response or a failure, plus the event list
     */
    public FallbackResult dispatch(List<ModelCandidate> ordered, CompletionRequest request, int maxAttempts) {
        return dispatch(ordered, request, maxAttempts, null);
    }

    /**
     * Drive the ordered chain with a {@link CircuitBreaker} consulted per candidate (M4-2).
     *
     * <p>For each candidate the breaker is asked {@link CircuitBreaker#allowRequest}:
     * <ul>
     *   <li><b>blocked (OPEN)</b>: the candidate is <b>not attempted</b>; a deterministic
     *       {@link FallbackOutcome#FAILED} event with {@code error_type = "circuit_open"} is emitted
     *       so the skip is observable in {@code fallback_events}, and the chain falls back to the
     *       next candidate. A skipped candidate does <b>not</b> consume the breaker's record signal.</li>
     *   <li><b>admitted</b>: the candidate is tried; on success {@link CircuitBreaker#recordSuccess}
     *       is called and the chain stops; on a {@link RuntimeException}
     *       {@link CircuitBreaker#recordFailure} is called and the chain falls back.</li>
     * </ul>
     *
     * <p>{@code breaker == null} reproduces the no-breaker behavior of
     * {@link #dispatch(List, CompletionRequest, int)} exactly, so the existing call paths are
     * unaffected (zero regression).
     *
     * <p>The attempt cap counts every candidate the chain advances past — both real attempts and
     * circuit-open skips — so {@code maxAttempts} remains a hard bound on chain length.
     */
    public FallbackResult dispatch(List<ModelCandidate> ordered, CompletionRequest request,
                                   int maxAttempts, CircuitBreaker breaker) {
        return dispatch(ordered, request, maxAttempts, breaker, null);
    }

    /**
     * Drive the ordered chain with a {@link CircuitBreaker} <i>and</i> a shared {@link RetryBudget}
     * (M4-3) consulted per fallback.
     *
     * <p>The <b>primary</b> attempt (index 0) never touches the budget — a healthy primary path is
     * never throttled. Before advancing to <b>any fallback candidate</b> (index &gt;= 1) and making a
     * real provider call, the chain spends one token via {@link RetryBudget#tryRetry()}:
     * <ul>
     *   <li><b>token available</b>: the token is consumed and the fallback proceeds (then the breaker,
     *       if present, gates the actual attempt as usual);</li>
     *   <li><b>budget exhausted</b>: the chain stops immediately — remaining candidates are
     *       <b>not</b> attempted (their providers' {@code complete} is never called), a deterministic
     *       {@link FallbackOutcome#FAILED} event with {@code error_type = "retry_budget_exhausted"} is
     *       emitted so the throttle is observable, and a failure result with that
     *       {@code lastErrorType} is returned.</li>
     * </ul>
     *
     * <p>A <b>circuit-open skip does not consume the budget</b>: the breaker is consulted first, and a
     * skipped candidate is not a real retry, so it must not burn a token. The budget gates only real
     * fallback provider calls, keeping the two signals composable.
     *
     * <p>{@code budget == null} reproduces {@link #dispatch(List, CompletionRequest, int, CircuitBreaker)}
     * exactly (zero regression); {@code breaker == null} is likewise honored, so all four combinations
     * compose.
     */
    public FallbackResult dispatch(List<ModelCandidate> ordered, CompletionRequest request,
                                   int maxAttempts, CircuitBreaker breaker, RetryBudget budget) {
        if (ordered == null || ordered.isEmpty()) {
            throw new IllegalArgumentException("ordered candidates must be non-empty");
        }
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }

        int cap = Math.min(Math.max(1, maxAttempts), ordered.size());
        List<FallbackEvent> events = new ArrayList<>(cap);
        String lastErrorType = null;

        for (int attempt = 0; attempt < cap; attempt++) {
            ModelCandidate candidate = ordered.get(attempt);
            if (breaker != null && !breaker.allowRequest(candidate.provider())) {
                // Circuit OPEN: skip without attempting and WITHOUT spending budget (not a real retry);
                // record the skip as an observable FAILED event and fall back to the next candidate.
                lastErrorType = CIRCUIT_OPEN;
                events.add(FallbackEvent.failed(attempt, candidate.provider(), candidate.model(), lastErrorType));
                continue;
            }
            if (attempt > 0 && budget != null && !budget.tryRetry()) {
                // Budget exhausted before this fallback's real provider call: stop the chain.
                // This candidate is NOT attempted (provider.complete never invoked).
                lastErrorType = RETRY_BUDGET_EXHAUSTED;
                events.add(FallbackEvent.failed(attempt, candidate.provider(), candidate.model(), lastErrorType));
                return FallbackResult.ofFailure(attempt, lastErrorType, events);
            }
            try {
                LlmProvider provider = providers.get(candidate.provider());
                CompletionResponse response = provider.complete(candidate.model(), request);
                if (response == null) {
                    throw new IllegalStateException("provider returned null response");
                }
                if (breaker != null) {
                    breaker.recordSuccess(candidate.provider());
                }
                events.add(FallbackEvent.success(attempt, candidate.provider(), candidate.model()));
                return FallbackResult.ofSuccess(response, candidate, attempt, events);
            } catch (RuntimeException e) {
                if (breaker != null) {
                    breaker.recordFailure(candidate.provider());
                }
                lastErrorType = errorType(e);
                events.add(FallbackEvent.failed(attempt, candidate.provider(), candidate.model(), lastErrorType));
            }
        }
        // Every attempted candidate failed; fallbacksUsed = attempts beyond the primary.
        return FallbackResult.ofFailure(cap - 1, lastErrorType, events);
    }

    /**
     * Deterministic {@code error_type} label: the exception message when present, otherwise the
     * exception's {@code simpleName}. No clock or network is consulted.
     */
    static String errorType(RuntimeException e) {
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return e.getClass().getSimpleName();
    }
}
