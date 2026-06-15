package com.example.gateway.guardrail;

/** Result of the input/output guardrail stages. Local rules only, not a safety guarantee. */
public enum GuardrailResult {
    PASS,
    BLOCKED_INPUT,
    BLOCKED_OUTPUT
}
