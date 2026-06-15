package com.example.gateway.provider;

/**
 * Resolves a provider id to its {@link LlmProvider} transport.
 */
@FunctionalInterface
public interface ProviderRegistry {

    /**
     * @return the provider for the id
     * @throws IllegalArgumentException if no provider is registered for the id
     */
    LlmProvider get(String providerId);
}
