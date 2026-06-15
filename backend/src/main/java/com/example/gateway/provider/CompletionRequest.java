package com.example.gateway.provider;

/**
 * Normalized completion request after the OpenAI-compatible layer.
 * {@code alias} is the gateway model alias; the router resolves it to a real model.
 */
public record CompletionRequest(String tenantId, String alias, String prompt, int maxTokens, boolean stream) {

    public CompletionRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias is required");
        }
        if (prompt == null) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }
}
