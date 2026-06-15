package com.example.gateway.resilience;

import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.router.ModelCandidate;

import java.util.List;

/**
 * Outcome of driving a {@link FallbackChain}: the successful response (if any), the
 * candidate that produced it, the 0-based number of fallbacks used (attempts beyond the
 * primary), and the ordered per-attempt {@link FallbackEvent} list for {@code fallback_events}.
 *
 * <p>On success the last event is {@link FallbackOutcome#SUCCESS} and any preceding events are
 * {@link FallbackOutcome#FAILED}. On total failure all events are {@code FAILED},
 * {@link #response()} is null, and {@link #lastErrorType()} carries the final attempt's label.
 */
public record FallbackResult(
        boolean success,
        CompletionResponse response,
        ModelCandidate chosen,
        int fallbacksUsed,
        String lastErrorType,
        List<FallbackEvent> events) {

    public FallbackResult {
        events = events == null ? List.of() : List.copyOf(events);
        if (success && response == null) {
            throw new IllegalArgumentException("a successful result must carry a response");
        }
        if (success && chosen == null) {
            throw new IllegalArgumentException("a successful result must carry the chosen candidate");
        }
        if (fallbacksUsed < 0) {
            throw new IllegalArgumentException("fallbacksUsed must be non-negative");
        }
    }

    static FallbackResult ofSuccess(CompletionResponse response, ModelCandidate chosen,
                                    int fallbacksUsed, List<FallbackEvent> events) {
        return new FallbackResult(true, response, chosen, fallbacksUsed, null, events);
    }

    static FallbackResult ofFailure(int fallbacksUsed, String lastErrorType, List<FallbackEvent> events) {
        return new FallbackResult(false, null, null, fallbacksUsed, lastErrorType, events);
    }
}
