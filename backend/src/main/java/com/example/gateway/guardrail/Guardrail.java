package com.example.gateway.guardrail;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;

/**
 * Input/output guardrail. Local rules only (banned terms, oversized input, PII masking
 * hook). This is not a safety guarantee; it produces a recorded result per request.
 */
public interface Guardrail {

    GuardrailResult inspectInput(CompletionRequest request);

    GuardrailResult inspectOutput(CompletionResponse response);
}
