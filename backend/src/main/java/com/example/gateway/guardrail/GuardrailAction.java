package com.example.gateway.guardrail;

/**
 * Per-rule verdict, projected 1:1 onto the {@code guardrail_events.action} TEXT column
 * ({@code PASS} / {@code BLOCK}). One stage runs many rules; a single {@code BLOCK} makes the
 * stage block ({@link GuardrailResult#BLOCKED_INPUT} / {@link GuardrailResult#BLOCKED_OUTPUT}).
 */
public enum GuardrailAction {
    PASS,
    BLOCK
}
