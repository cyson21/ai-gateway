package com.example.gateway.provider;

/**
 * OpenAI-compatible function tool call returned by a provider.
 */
public record ToolCall(String id, String type, FunctionCall function) {

    public ToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (type == null || type.isBlank()) {
            type = "function";
        }
        if (function == null) {
            throw new IllegalArgumentException("function is required");
        }
    }

    public record FunctionCall(String name, String arguments) {

        public FunctionCall {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("function name is required");
            }
            if (arguments == null) {
                arguments = "{}";
            }
        }
    }
}
