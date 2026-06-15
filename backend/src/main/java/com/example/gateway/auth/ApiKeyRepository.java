package com.example.gateway.auth;

import reactor.core.publisher.Mono;

/**
 * Lookup abstraction for API key authentication. Returns the key-plus-tenant view for a
 * hashed key, or an empty {@link Mono} when no key matches. Kept as an interface so the
 * {@link ApiKeyAuthFilter} can be unit-tested with an in-memory fake, independent of R2DBC.
 */
public interface ApiKeyRepository {

    Mono<ApiKeyRecord> findByKeyHash(String keyHash);
}
