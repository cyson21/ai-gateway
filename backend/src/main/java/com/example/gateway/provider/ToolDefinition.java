package com.example.gateway.provider;

import java.util.Map;

/**
 * OpenAI-compatible tool definition carried through the normalized provider request.
 */
public record ToolDefinition(String type, FunctionDefinition function) {

    public ToolDefinition {
        if (type == null || type.isBlank()) {
            type = "function";
        }
        if (function == null) {
            throw new IllegalArgumentException("function is required");
        }
    }

    public record FunctionDefinition(String name, String description, Map<String, Object> parameters) {

        public FunctionDefinition {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("function name is required");
            }
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }
}
