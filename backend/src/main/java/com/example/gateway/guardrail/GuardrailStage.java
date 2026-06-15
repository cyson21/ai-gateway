package com.example.gateway.guardrail;

/**
 * Stage a guardrail rule runs in, projected 1:1 onto the {@code guardrail_events.stage} TEXT
 * column ({@code INPUT} / {@code OUTPUT}).
 */
public enum GuardrailStage {
    INPUT,
    OUTPUT
}
