package com.example.gateway.auth;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * R2DBC-backed {@link ApiKeyRepository}. Joins {@code api_keys} to {@code tenants} so a single
 * round trip returns both status flags; the filter decides authorization from them.
 */
@Repository
public class R2dbcApiKeyRepository implements ApiKeyRepository {

    private static final String LOOKUP = """
            SELECT k.tenant_id AS tenant_id,
                   k.status    AS key_status,
                   t.status    AS tenant_status
            FROM api_keys k
            JOIN tenants t ON t.id = k.tenant_id
            WHERE k.key_hash = :keyHash
            """;

    private final DatabaseClient client;

    public R2dbcApiKeyRepository(DatabaseClient client) {
        this.client = client;
    }

    @Override
    public Mono<ApiKeyRecord> findByKeyHash(String keyHash) {
        return client.sql(LOOKUP)
                .bind("keyHash", keyHash)
                .map((row, meta) -> new ApiKeyRecord(
                        row.get("tenant_id", String.class),
                        row.get("key_status", String.class),
                        row.get("tenant_status", String.class)))
                .one();
    }
}
