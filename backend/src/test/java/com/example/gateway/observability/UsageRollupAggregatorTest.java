package com.example.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * D2: fold {@code request_logs} ({@link RequestLogRow} lists) into daily / per-model
 * {@code usage_rollups} buckets keyed by {@code (tenant, day, model)}, and prove the rollup
 * counts/cost reconcile against the source rows (preservation).
 *
 * <p>D2 is a pure aggregation function, so rows are built directly (no pipeline needed). {@code day}
 * comes from the injected {@code createdAtMillis} as a UTC date — boundaries are deterministic.
 */
class UsageRollupAggregatorTest {

    private final UsageRollupAggregator aggregator = new UsageRollupAggregator();

    // ---- row builders ------------------------------------------------------

    /** Millis-since-epoch for a UTC date at midnight. */
    private static long utcMillis(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** Millis for a UTC date at a given time-of-day (to exercise same-day grouping). */
    private static long utcMillis(LocalDate day, LocalTime time) {
        return day.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** A provider-served row (model present). */
    private static RequestLogRow served(String tenant, String model, long createdAtMillis,
                                        int promptTokens, int completionTokens, long cost,
                                        String cacheType, int fallbackCount) {
        return new RequestLogRow(tenant, "gpt-4o", "ROUTED", "fake", model,
                promptTokens, completionTokens, 5L, cost, cacheType, fallbackCount,
                "PASS", "ALLOWED", null, createdAtMillis);
    }

    /** A rejected/blocked/failed row: no provider call -> null model. */
    private static RequestLogRow noModel(String tenant, long createdAtMillis, String budgetOutcome) {
        return new RequestLogRow(tenant, "gpt-4o", "ROUTED", null, null,
                0, 0, 5L, 0L, "NONE", 0, "PASS", budgetOutcome, "rejected", createdAtMillis);
    }

    private static UsageRollup find(List<UsageRollup> rollups, String tenant, LocalDate day, String model) {
        return rollups.stream()
                .filter(r -> r.tenantId().equals(tenant) && r.day().equals(day) && r.model().equals(model))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rollup for " + tenant + "/" + day + "/" + model));
    }

    // ---- same key folds into one bucket with exact sums --------------------

    @Test
    void sameTenantDayModelFoldsIntoOneRollupWithExactSums() {
        LocalDate day = LocalDate.of(2026, 6, 10);
        List<RequestLogRow> rows = List.of(
                served("t1", "gpt-4o", utcMillis(day, LocalTime.of(1, 0)), 10, 20, 100, "NONE", 0),
                served("t1", "gpt-4o", utcMillis(day, LocalTime.of(9, 0)), 5, 5, 50, "NONE", 1),
                served("t1", "gpt-4o", utcMillis(day, LocalTime.of(23, 0)), 7, 3, 30, "EXACT", 0));

        List<UsageRollup> rollups = aggregator.aggregate(rows);

        assertThat(rollups).hasSize(1);
        UsageRollup r = rollups.get(0);
        assertThat(r.tenantId()).isEqualTo("t1");
        assertThat(r.day()).isEqualTo(day);
        assertThat(r.model()).isEqualTo("gpt-4o");
        assertThat(r.calls()).isEqualTo(3);
        assertThat(r.tokens()).isEqualTo(10 + 20 + 5 + 5 + 7 + 3);
        assertThat(r.cost()).isEqualTo(100 + 50 + 30);
        assertThat(r.cacheHits()).isEqualTo(1); // one EXACT
        assertThat(r.fallbacks()).isEqualTo(1);
    }

    // ---- different model / day / tenant -> separate buckets ----------------

    @Test
    void differentModelDayAndTenantSplitIntoSeparateBuckets() {
        LocalDate d1 = LocalDate.of(2026, 6, 10);
        LocalDate d2 = LocalDate.of(2026, 6, 11);
        List<RequestLogRow> rows = List.of(
                served("t1", "gpt-4o", utcMillis(d1), 1, 1, 10, "NONE", 0),
                served("t1", "claude-3", utcMillis(d1), 2, 2, 20, "NONE", 0), // different model
                served("t1", "gpt-4o", utcMillis(d2), 3, 3, 30, "NONE", 0),   // different day
                served("t2", "gpt-4o", utcMillis(d1), 4, 4, 40, "NONE", 0));  // different tenant

        List<UsageRollup> rollups = aggregator.aggregate(rows);

        assertThat(rollups).hasSize(4);
        assertThat(find(rollups, "t1", d1, "gpt-4o").cost()).isEqualTo(10);
        assertThat(find(rollups, "t1", d1, "claude-3").cost()).isEqualTo(20);
        assertThat(find(rollups, "t1", d2, "gpt-4o").cost()).isEqualTo(30);
        assertThat(find(rollups, "t2", d1, "gpt-4o").cost()).isEqualTo(40);
        assertThat(rollups).allSatisfy(r -> assertThat(r.calls()).isEqualTo(1));
    }

    // ---- cache hits and fallbacks ------------------------------------------

    @Test
    void cacheHitsCountBothExactAndSemanticAndFallbacksSum() {
        LocalDate day = LocalDate.of(2026, 6, 10);
        List<RequestLogRow> rows = List.of(
                served("t1", "gpt-4o", utcMillis(day, LocalTime.of(1, 0)), 1, 1, 0, "EXACT", 0),
                served("t1", "gpt-4o", utcMillis(day, LocalTime.of(2, 0)), 1, 1, 0, "SEMANTIC", 0),
                served("t1", "gpt-4o", utcMillis(day, LocalTime.of(3, 0)), 1, 1, 5, "NONE", 2),
                served("t1", "gpt-4o", utcMillis(day, LocalTime.of(4, 0)), 1, 1, 5, "NONE", 3));

        UsageRollup r = aggregator.aggregate(rows).get(0);

        assertThat(r.calls()).isEqualTo(4);
        assertThat(r.cacheHits()).isEqualTo(2); // EXACT + SEMANTIC
        assertThat(r.cost()).isEqualTo(0 + 0 + 5 + 5);
        assertThat(r.fallbacks()).isEqualTo(5); // 0 + 0 + 2 + 3
    }

    // ---- day boundary is UTC and deterministic -----------------------------

    @Test
    void dayBoundaryIsUtc() {
        LocalDate day = LocalDate.of(2026, 6, 10);
        long endOfDay = utcMillis(day, LocalTime.of(23, 59, 59)); // 2026-06-10 UTC
        long startNext = utcMillis(day.plusDays(1), LocalTime.of(0, 0, 0)); // 2026-06-11 UTC
        List<RequestLogRow> rows = List.of(
                served("t1", "gpt-4o", endOfDay, 1, 1, 10, "NONE", 0),
                served("t1", "gpt-4o", startNext, 1, 1, 20, "NONE", 0));

        List<UsageRollup> rollups = aggregator.aggregate(rows);

        assertThat(rollups).hasSize(2);
        assertThat(find(rollups, "t1", day, "gpt-4o").cost()).isEqualTo(10);
        assertThat(find(rollups, "t1", day.plusDays(1), "gpt-4o").cost()).isEqualTo(20);
    }

    // ---- model = null rows are excluded ------------------------------------

    @Test
    void nullModelRowsAreExcludedFromEveryRollup() {
        LocalDate day = LocalDate.of(2026, 6, 10);
        List<RequestLogRow> rows = List.of(
                served("t1", "gpt-4o", utcMillis(day), 1, 1, 10, "NONE", 0),
                noModel("t1", utcMillis(day), "RATE_LIMITED"),
                noModel("t1", utcMillis(day), "BUDGET_EXCEEDED"),
                noModel("t1", utcMillis(day), "ALLOWED")); // input guardrail block / failed

        List<UsageRollup> rollups = aggregator.aggregate(rows);

        // only the one served row produced a rollup
        assertThat(rollups).hasSize(1);
        UsageRollup r = rollups.get(0);
        assertThat(r.calls()).isEqualTo(1);
        assertThat(r.tokens()).isEqualTo(2);
        assertThat(r.cost()).isEqualTo(10);
    }

    // ---- preservation: rollup totals reconcile against source rows ---------

    @Test
    void rollupTotalsPreserveSourceTotalsOverModelBearingRows() {
        LocalDate d1 = LocalDate.of(2026, 6, 10);
        LocalDate d2 = LocalDate.of(2026, 6, 11);
        List<RequestLogRow> rows = List.of(
                served("t1", "gpt-4o", utcMillis(d1, LocalTime.of(1, 0)), 10, 20, 100, "NONE", 1),
                served("t1", "gpt-4o", utcMillis(d1, LocalTime.of(2, 0)), 5, 5, 50, "EXACT", 0),
                served("t1", "claude-3", utcMillis(d1), 3, 3, 30, "SEMANTIC", 2),
                served("t1", "gpt-4o", utcMillis(d2), 8, 2, 40, "NONE", 0),
                served("t2", "gpt-4o", utcMillis(d1), 4, 4, 60, "NONE", 3),
                // model=null rows: must not affect any preservation total
                noModel("t1", utcMillis(d1), "RATE_LIMITED"),
                noModel("t2", utcMillis(d2), "BUDGET_EXCEEDED"));

        List<UsageRollup> rollups = aggregator.aggregate(rows);

        // expected over model-bearing rows only
        long modelRows = rows.stream().filter(r -> r.chosenModel() != null).count();
        long srcTokens = rows.stream().filter(r -> r.chosenModel() != null)
                .mapToLong(r -> (long) r.promptTokens() + r.completionTokens()).sum();
        long srcCost = rows.stream().filter(r -> r.chosenModel() != null).mapToLong(RequestLogRow::cost).sum();
        long srcCacheHits = rows.stream().filter(r -> r.chosenModel() != null)
                .filter(r -> r.cacheType().equals("EXACT") || r.cacheType().equals("SEMANTIC")).count();
        long srcFallbacks = rows.stream().filter(r -> r.chosenModel() != null)
                .mapToLong(RequestLogRow::fallbackCount).sum();

        assertThat(rollups.stream().mapToLong(UsageRollup::calls).sum()).isEqualTo(modelRows);
        assertThat(rollups.stream().mapToLong(UsageRollup::tokens).sum()).isEqualTo(srcTokens);
        assertThat(rollups.stream().mapToLong(UsageRollup::cost).sum()).isEqualTo(srcCost);
        assertThat(rollups.stream().mapToLong(UsageRollup::cacheHits).sum()).isEqualTo(srcCacheHits);
        assertThat(rollups.stream().mapToLong(UsageRollup::fallbacks).sum()).isEqualTo(srcFallbacks);

        // bucket count reflects distinct (tenant, day, model) keys among model-bearing rows
        assertThat(rollups).hasSize(4); // (t1,d1,gpt-4o),(t1,d1,claude-3),(t1,d2,gpt-4o),(t2,d1,gpt-4o)
    }

    // ---- store entry points ------------------------------------------------

    @Test
    void aggregatesFromStoreAllAndPerTenant() {
        LocalDate day = LocalDate.of(2026, 6, 10);
        InMemoryRequestLogStore store = new InMemoryRequestLogStore(() -> utcMillis(day));
        store.append(new RequestRecordStub("t1", "gpt-4o").toRecord());
        store.append(new RequestRecordStub("t1", "gpt-4o").toRecord());
        store.append(new RequestRecordStub("t2", "gpt-4o").toRecord());

        // all() -> two tenant buckets
        assertThat(aggregator.aggregate(store)).hasSize(2);
        // forTenant -> single bucket with 2 calls
        List<UsageRollup> t1 = aggregator.aggregateTenant(store, "t1");
        assertThat(t1).hasSize(1);
        assertThat(t1.get(0).calls()).isEqualTo(2);
    }

    /** Minimal helper to push a served record through the store's append/projection path. */
    private record RequestRecordStub(String tenant, String model) {
        RequestRecord toRecord() {
            return new RequestRecord(tenant, "gpt-4o", com.example.gateway.api.PipelineMode.ROUTED,
                    "fake", model, 1, 1, 5L, 10L, com.example.gateway.cache.CacheType.NONE, 0,
                    com.example.gateway.guardrail.GuardrailResult.PASS,
                    com.example.gateway.quota.QuotaOutcome.ALLOWED, null);
        }
    }
}
