package com.example.gateway.provider;

/**
 * Provider abstraction. Real transports (OpenAI, Anthropic) and the fake provider
 * implement this interface so the gateway pipeline never depends on a vendor SDK.
 *
 * <p>The foundation harness uses the synchronous {@link #complete} method.
 * Streaming is layered on top in a later slice via the reactive web layer.
 */
public interface LlmProvider {

    /** Stable provider id, e.g. {@code "fake"}, {@code "openai"}, {@code "anthropic"}. */
    String name();

    /** Whether this provider is currently considered healthy by the router. */
    boolean healthy();

    /** Run a completion against the given concrete model. */
    CompletionResponse complete(String model, CompletionRequest request);
}
