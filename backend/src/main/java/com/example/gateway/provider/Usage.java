package com.example.gateway.provider;

/**
 * Token usage reported by a provider for a single completion.
 */
public record Usage(int promptTokens, int completionTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public static Usage zero() {
        return new Usage(0, 0);
    }
}
