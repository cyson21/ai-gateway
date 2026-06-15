package com.example.gateway.observability;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Sink for per-request observability. Every path through {@link com.example.gateway.api.GatewayPipeline}
 * (quota reject, input/output guardrail block, cache hit, failed, ok) produces a fully populated
 * {@link RequestRecord}; {@link #append} maps it to a {@link RequestLogRow} and persists one row, so
 * no request escapes {@code request_logs}.
 *
 * <p>This is the same store-port shape as the quota/cache slices. The in-memory fake proves the
 * mapping and the all-paths-record contract; real R2DBC/PostgreSQL {@code request_logs} INSERT,
 * the {@code (tenant_id, created_at)} index, and concurrency are the E1 slice.
 */
public interface RequestLogStore {

    /**
     * Map {@code record} to a row (stamped with the store's clock) and persist it.
     *
     * @return the persisted row
     */
    RequestLogRow append(RequestRecord record);

    /** Append using an explicit timestamp source (lets callers inject a deterministic clock). */
    RequestLogRow append(RequestRecord record, LongSupplier clockMillis);

    /** All rows for a tenant, in append order. Test/assertion read side. */
    List<RequestLogRow> forTenant(String tenantId);

    /** All rows across tenants, in append order. */
    List<RequestLogRow> all();
}
