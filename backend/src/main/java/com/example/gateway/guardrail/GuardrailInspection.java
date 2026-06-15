package com.example.gateway.guardrail;

import java.util.List;

/**
 * Detailed outcome of one guardrail stage: the per-rule {@link GuardrailEvent} list (one event per
 * rule that ran, in rule order) plus the rolled-up stage {@link GuardrailResult}. The result is
 * {@code PASS} when every event passed, otherwise the stage's blocked result
 * ({@link GuardrailResult#BLOCKED_INPUT} / {@link GuardrailResult#BLOCKED_OUTPUT}).
 *
 * <p>The {@link Guardrail} interface only exposes the rolled-up {@link GuardrailResult}; this richer
 * view is produced by {@link RuleBasedGuardrail} so callers can record the per-rule events into a
 * {@link GuardrailEventStore}.
 */
public record GuardrailInspection(GuardrailResult result, List<GuardrailEvent> events) {

    public GuardrailInspection {
        if (result == null) {
            throw new IllegalArgumentException("result is required");
        }
        events = List.copyOf(events == null ? List.of() : events);
    }

    public boolean blocked() {
        return result != GuardrailResult.PASS;
    }
}
