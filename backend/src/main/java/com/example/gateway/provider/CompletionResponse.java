package com.example.gateway.provider;

/**
 * Normalized completion response returned by a provider.
 */
public record CompletionResponse(String content, String model, Usage usage) {

    public CompletionResponse {
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (usage == null) {
            usage = Usage.zero();
        }
    }
}
