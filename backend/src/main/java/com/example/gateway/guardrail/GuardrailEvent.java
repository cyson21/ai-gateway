package com.example.gateway.guardrail;

/**
 * Projection of one guardrail-rule evaluation onto the {@code guardrail_events} columns
 * (V1 schema: stage, rule, action). The enums {@code stage} and {@code action} map to their
 * {@code name()} TEXT at persistence time; {@code rule} is the deterministic rule identifier.
 *
 * <p>A {@link RuleBasedGuardrail} stage produces one event per rule it runs; a single
 * {@link GuardrailAction#BLOCK} event makes the stage block. The {@code request_id} FK is not
 * modeled here: this slice produces the per-request event list only, and the FK wiring to
 * {@code request_logs} is a later persistence slice (same approach as D1 / M4-1).
 */
public record GuardrailEvent(GuardrailStage stage, String rule, GuardrailAction action) {

    public GuardrailEvent {
        if (stage == null) {
            throw new IllegalArgumentException("stage is required");
        }
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("rule is required");
        }
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
    }

    public static GuardrailEvent pass(GuardrailStage stage, String rule) {
        return new GuardrailEvent(stage, rule, GuardrailAction.PASS);
    }

    public static GuardrailEvent block(GuardrailStage stage, String rule) {
        return new GuardrailEvent(stage, rule, GuardrailAction.BLOCK);
    }

    public boolean blocked() {
        return action == GuardrailAction.BLOCK;
    }
}
