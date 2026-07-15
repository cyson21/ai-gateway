package com.example.gateway.web;

import java.util.List;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.ToolDefinition;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI-compatible {@code /v1/chat/completions} request body (the subset the gateway
 * consumes). {@code model} is the gateway model alias; the router resolves it to a real
 * model. Messages are flattened into the normalized {@link CompletionRequest#prompt()};
 * structured multi-turn handling is a later slice.
 */
public record ChatCompletionRequest(
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<Message> messages,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("stream") Boolean stream,
        @JsonProperty("tools") List<ToolDefinition> tools,
        @JsonProperty("tool_choice") String toolChoice) {

    public ChatCompletionRequest(String model, List<Message> messages, Integer maxTokens, Boolean stream) {
        this(model, messages, maxTokens, stream, List.of(), null);
    }

    /** Default completion budget when the caller omits {@code max_tokens}. */
    static final int DEFAULT_MAX_TOKENS = 256;

    public record Message(@JsonProperty("role") String role, @JsonProperty("content") String content) {
    }

    /**
     * Map to the normalized request, attaching the tenant resolved by the auth filter.
     * Field-level validation (blank alias, non-positive tokens) is enforced by
     * {@link CompletionRequest}; a missing, empty, or null-containing message list is rejected
     * here as a malformed body.
     */
    public CompletionRequest toCompletionRequest(String tenantId) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }
        if (messages.stream().anyMatch(message -> message == null)) {
            throw new IllegalArgumentException("messages must not contain null elements");
        }
        int tokens = (maxTokens == null || maxTokens <= 0) ? DEFAULT_MAX_TOKENS : maxTokens;
        boolean streaming = stream != null && stream;
        return new CompletionRequest(tenantId, model, flattenMessages(), tokens, streaming, tools, toolChoice);
    }

    /** Flatten chat turns into a single deterministic prompt: {@code role: content} per line. */
    private String flattenMessages() {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(message.role() == null ? "user" : message.role())
                    .append(": ")
                    .append(message.content() == null ? "" : message.content());
        }
        return sb.toString();
    }
}
