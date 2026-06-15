package com.example.gateway.observability;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure aggregation of {@code request_logs} ({@link RequestLogRow} lists) into daily / per-model
 * {@code usage_rollups} buckets keyed by {@code (tenant_id, day, model)}.
 *
 * <p>The aggregation is a deterministic fold — same input rows always fold to the same rollups — so
 * no store fake is needed. {@code day} is derived from each row's injected {@code createdAtMillis} as
 * a UTC {@link LocalDate} (no system clock), reflecting the schema's {@code DATE} column.
 *
 * <h2>{@code model = null} rows (no provider call)</h2>
 * Rows where {@code chosenModel} is null — quota/budget rejects, input/output guardrail blocks, and
 * failed/no-candidate paths — never reached a provider, so they carry <b>no per-model usage</b>.
 * Because {@code usage_rollups} is a per-model dimension (and its {@code model} column is
 * {@code NOT NULL}), these rows are <b>excluded</b> from every rollup. The preservation invariants
 * are defined against the rows that <i>do</i> carry a model:
 * <ul>
 *   <li>Σ {@code rollup.calls} == number of model-bearing source rows</li>
 *   <li>Σ {@code rollup.tokens} == Σ tokens over model-bearing rows</li>
 *   <li>Σ {@code rollup.cost} == Σ cost over model-bearing rows</li>
 *   <li>Σ {@code rollup.cacheHits} == number of model-bearing cache-hit rows</li>
 *   <li>Σ {@code rollup.fallbacks} == Σ fallbackCount over model-bearing rows</li>
 * </ul>
 * (Cache-hit rows carry a non-null {@code chosenModel}, so they are included and counted.)
 */
public final class UsageRollupAggregator {

    /** Aggregate every tenant's rows in {@code store.all()}. */
    public List<UsageRollup> aggregate(RequestLogStore store) {
        return aggregate(store.all());
    }

    /** Aggregate a single tenant's rows. */
    public List<UsageRollup> aggregateTenant(RequestLogStore store, String tenantId) {
        return aggregate(store.forTenant(tenantId));
    }

    /**
     * Fold rows into {@code (tenant, day, model)} rollups. Rows with a null {@code chosenModel} are
     * excluded (no provider call -> no per-model usage). Bucket iteration order follows first
     * appearance, so the result is deterministic.
     */
    public List<UsageRollup> aggregate(List<RequestLogRow> rows) {
        Map<Key, Accumulator> buckets = new LinkedHashMap<>();
        for (RequestLogRow row : rows) {
            if (row == null || row.chosenModel() == null) {
                continue; // no provider call -> not a per-model usage row
            }
            LocalDate day = dayOf(row.createdAtMillis());
            Key key = new Key(row.tenantId(), day, row.chosenModel());
            buckets.computeIfAbsent(key, k -> new Accumulator()).add(row);
        }

        List<UsageRollup> out = new ArrayList<>(buckets.size());
        for (Map.Entry<Key, Accumulator> e : buckets.entrySet()) {
            Key k = e.getKey();
            Accumulator a = e.getValue();
            out.add(new UsageRollup(k.tenantId(), k.day(), k.model(),
                    a.calls, a.tokens, a.cost, a.cacheHits, a.fallbacks));
        }
        return List.copyOf(out);
    }

    /** UTC day of a millis-since-epoch instant (deterministic, no system clock). */
    static LocalDate dayOf(long createdAtMillis) {
        return Instant.ofEpochMilli(createdAtMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static boolean isCacheHit(RequestLogRow row) {
        String type = row.cacheType();
        return "EXACT".equals(type) || "SEMANTIC".equals(type);
    }

    private record Key(String tenantId, LocalDate day, String model) {
    }

    private static final class Accumulator {
        private long calls;
        private long tokens;
        private long cost;
        private long cacheHits;
        private long fallbacks;

        void add(RequestLogRow row) {
            calls += 1;
            tokens += (long) row.promptTokens() + row.completionTokens();
            cost += row.cost();
            if (isCacheHit(row)) {
                cacheHits += 1;
            }
            fallbacks += row.fallbackCount();
        }
    }
}
