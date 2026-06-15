package com.example.gateway.observability;

import java.time.LocalDate;

/**
 * One {@code usage_rollups} row: the daily / per-model aggregate of {@code request_logs} rows that
 * share a {@code (tenant_id, day, model)} key (1:1 with the V1 schema, mirroring its
 * {@code UNIQUE (tenant_id, day, model)}).
 *
 * <ul>
 *   <li>{@code calls} — number of source rows in the bucket</li>
 *   <li>{@code tokens} — Σ(promptTokens + completionTokens)</li>
 *   <li>{@code cost} — Σ cost</li>
 *   <li>{@code cacheHits} — rows whose {@code cacheType} is EXACT or SEMANTIC</li>
 *   <li>{@code fallbacks} — Σ fallbackCount</li>
 * </ul>
 *
 * <p>{@code day} is a UTC {@link LocalDate} derived deterministically from the source row's injected
 * {@code createdAtMillis} (no wall clock). Real PostgreSQL {@code usage_rollups} UPSERT / incremental
 * aggregation / concurrency is the E1 slice; this record is the pure aggregation result.
 */
public record UsageRollup(
        String tenantId,
        LocalDate day,
        String model,
        long calls,
        long tokens,
        long cost,
        long cacheHits,
        long fallbacks) {
}
