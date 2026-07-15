package com.example.gateway.web;

import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /** Public API ceiling used to reject accidental or overflow-prone token budgets. */
    static final int MAX_MAX_TOKENS = 8_192;

    private static final Set<String> SUPPORTED_ROLES =
            Set.of("system", "developer", "user", "assistant", "tool");
    private static final Set<String> SUPPORTED_TOOL_CHOICES = Set.of("none", "auto", "required");

    public record Message(@JsonProperty("role") String role, @JsonProperty("content") String content) {
    }

    /**
     * Map to the normalized request, attaching the tenant resolved by the auth filter.
     * This boundary rejects unsupported roles, blank content, invalid token ceilings, and invalid
     * tool combinations before constructing the provider-facing request.
     */
    public CompletionRequest toCompletionRequest(String tenantId) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }
        if (messages.stream().anyMatch(message -> message == null)) {
            throw new IllegalArgumentException("messages must not contain null elements");
        }
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message.role() == null || message.role().isBlank()) {
                throw new IllegalArgumentException("messages[" + index + "].role is required");
            }
            String role = normalize(message.role());
            if (!SUPPORTED_ROLES.contains(role)) {
                throw new IllegalArgumentException("messages[" + index + "].role is unsupported");
            }
            if (message.content() == null || message.content().isBlank()) {
                throw new IllegalArgumentException("messages[" + index + "].content is required");
            }
        }
        int tokens = maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens;
        if (tokens <= 0) {
            throw new IllegalArgumentException("max_tokens must be positive");
        }
        if (tokens > MAX_MAX_TOKENS) {
            throw new IllegalArgumentException("max_tokens must be at most " + MAX_MAX_TOKENS);
        }
        List<ToolDefinition> suppliedTools = tools == null ? List.of() : tools;
        if (suppliedTools.stream().anyMatch(tool -> tool == null)) {
            throw new IllegalArgumentException("tools must not contain null elements");
        }
        List<ToolDefinition> normalizedTools = List.copyOf(suppliedTools);
        String normalizedToolChoice = toolChoice == null || toolChoice.isBlank() ? null : normalize(toolChoice);
        if (normalizedToolChoice != null && !SUPPORTED_TOOL_CHOICES.contains(normalizedToolChoice)) {
            throw new IllegalArgumentException("tool_choice is unsupported");
        }
        if (normalizedToolChoice != null && !"none".equals(normalizedToolChoice) && normalizedTools.isEmpty()) {
            throw new IllegalArgumentException("tool_choice requires at least one tool");
        }
        boolean streaming = stream != null && stream;
        return new CompletionRequest(
                tenantId, model, flattenMessages(), tokens, streaming, normalizedTools, normalizedToolChoice);
    }

    /** Flatten chat turns into a single deterministic prompt: {@code role: content} per line. */
    private String flattenMessages() {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(normalize(message.role()))
                    .append(": ")
                    .append(message.content());
        }
        return sb.toString();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
