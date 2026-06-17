package com.example.gateway.provider;

import java.util.List;

/**
 * Normalized completion response returned by a provider.
 */
public record CompletionResponse(String content, String model, Usage usage, List<ToolCall> toolCalls) {

    public CompletionResponse(String content, String model, Usage usage) {
        this(content, model, usage, List.of());
    }

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
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
