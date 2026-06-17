package com.example.gateway.provider;

/**
 * Deterministic provider for tests, local demos, and the public static demo.
 * It performs no network calls and reports usage derived from input length so
 * cost and token accounting can be exercised without a live transport.
 */
public final class FakeLlmProvider implements LlmProvider {

    private final String name;
    private volatile boolean healthy;

    public FakeLlmProvider() {
        this("fake");
    }

    public FakeLlmProvider(String name) {
        this.name = name;
        this.healthy = true;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean healthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    @Override
    public CompletionResponse complete(String model, CompletionRequest request) {
        if (!healthy) {
            throw new IllegalStateException("provider '" + name + "' is unhealthy");
        }
        int promptTokens = estimateTokens(request.prompt());
        if (request.hasTools() && !"none".equalsIgnoreCase(request.toolChoice())) {
            ToolDefinition tool = request.tools().get(0);
            String arguments = "{\"query\":\"" + jsonString(request.prompt()) + "\"}";
            ToolCall call = new ToolCall(
                    "call_" + Math.abs((tool.function().name() + request.prompt()).hashCode()),
                    "function",
                    new ToolCall.FunctionCall(tool.function().name(), arguments));
            int completionTokens = Math.min(request.maxTokens(), Math.max(1, estimateTokens(arguments)));
            return new CompletionResponse("", model, new Usage(promptTokens, completionTokens), java.util.List.of(call));
        }
        String content = "[" + name + ":" + model + "] echo: " + request.prompt();
        int completionTokens = Math.min(request.maxTokens(), estimateTokens(content));
        return new CompletionResponse(content, model, new Usage(promptTokens, completionTokens));
    }

    /** Deterministic token estimate: roughly one token per four characters, min 1. */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private static String jsonString(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
