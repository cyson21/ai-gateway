package com.example.gateway.eval;

import com.example.gateway.api.PipelineMode;
import com.example.gateway.cache.CacheType;
import com.example.gateway.guardrail.GuardrailResult;
import com.example.gateway.observability.RequestRecord;
import com.example.gateway.quota.QuotaOutcome;

import java.util.List;

/**
 * Deterministic per-mode fold of one {@link EvalResult} (the records one {@link PipelineMode}
 * produced over a golden set) into a single comparison row.
 *
 * <p>This is a pure aggregation — no clock, randomness, network, or file IO. Every metric is an
 * exact sum or count over the input records, in input order. Ratios are kept <b>deterministic</b>
 * by storing the integer numerator/denominator rather than a floating-point value: callers that
 * need a percentage derive it from the integers via {@link Ratio}, which renders a fixed-decimal
 * string using only integer arithmetic (so the same input always yields a byte-identical string).
 *
 * <p>Classification of each record mirrors {@code GatewayPipeline}'s terminal states:
 * <ul>
 *   <li><b>cacheHit</b>: {@link RequestRecord#servedFromCache()} (EXACT/SEMANTIC).</li>
 *   <li><b>quota rejected</b>: {@code budgetOutcome != ALLOWED} (RATE_LIMITED / BUDGET_EXCEEDED).</li>
 *   <li><b>guardrail blocked</b>: {@code guardrailResult} is BLOCKED_INPUT or BLOCKED_OUTPUT.</li>
 *   <li><b>ok</b>: a record that reached a provider and returned a response — not cached, not
 *       rejected, not blocked, and with a {@code chosenProvider}. (Cache hits are counted under
 *       {@code cacheHitCount}, not {@code okCount}, so the buckets are disjoint.)</li>
 *   <li><b>failed</b>: everything left over (e.g. "all candidates failed" / "no healthy candidate"),
 *       derivable as {@code requestCount - okCount - cacheHitCount - rejectedCount - blockedCount}.</li>
 * </ul>
 * Buckets {ok, cacheHit, rejected, blocked, failed} partition every record exactly once.
 *
 * @param mode             the mode these records were produced under
 * @param requestCount     number of records folded (golden-set size)
 * @param totalPromptTokens     summed prompt tokens
 * @param totalCompletionTokens summed completion tokens
 * @param totalCost        summed cost (same micro-unit as {@link RequestRecord#cost()})
 * @param totalLatencyMs   summed latency across all records
 * @param cacheHitCount    records served from cache
 * @param okCount          provider-served successes (cache hits excluded)
 * @param rejectedCount    records rejected by the quota stage
 * @param blockedCount     records blocked by an input/output guardrail
 * @param failedCount      records that produced no usable response for another reason
 * @param fallbackCount    summed {@link RequestRecord#fallbackCount()} (retries/fallbacks attempted)
 */
public record ModeSummary(
        PipelineMode mode,
        int requestCount,
        long totalPromptTokens,
        long totalCompletionTokens,
        long totalCost,
        long totalLatencyMs,
        int cacheHitCount,
        int okCount,
        int rejectedCount,
        int blockedCount,
        int failedCount,
        long fallbackCount) {

    public ModeSummary {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
    }

    /** Total tokens across all folded records. */
    public long totalTokens() {
        return totalPromptTokens + totalCompletionTokens;
    }

    /**
     * Cache-hit rate as an exact {@code cacheHitCount / requestCount} {@link Ratio}. An empty mode
     * (no records) yields {@code 0/0}, which {@link Ratio} renders as a deterministic zero.
     */
    public Ratio cacheHitRate() {
        return new Ratio(cacheHitCount, requestCount);
    }

    /**
     * Average latency by deterministic integer (truncating) division: {@code totalLatencyMs /
     * requestCount}, or {@code 0} when there are no records. No floating point, so the value is
     * reproducible byte-for-byte.
     */
    public long averageLatencyMs() {
        return requestCount == 0 ? 0L : totalLatencyMs / requestCount;
    }

    /**
     * Fold one mode's {@link EvalResult} into a summary. Pure: a single pass over the records in
     * input order, accumulating exact sums/counts.
     */
    public static ModeSummary fold(EvalResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result is required");
        }
        List<RequestRecord> records = result.records();

        long promptTokens = 0L;
        long completionTokens = 0L;
        long cost = 0L;
        long latency = 0L;
        long fallbacks = 0L;
        int cacheHits = 0;
        int ok = 0;
        int rejected = 0;
        int blocked = 0;
        int failed = 0;

        for (RequestRecord r : records) {
            promptTokens += r.promptTokens();
            completionTokens += r.completionTokens();
            cost += r.cost();
            latency += r.latencyMs();
            fallbacks += r.fallbackCount();

            // Disjoint, ordered classification — first matching bucket wins.
            if (r.budgetOutcome() != null && r.budgetOutcome() != QuotaOutcome.ALLOWED) {
                rejected++;
            } else if (r.guardrailResult() == GuardrailResult.BLOCKED_INPUT
                    || r.guardrailResult() == GuardrailResult.BLOCKED_OUTPUT) {
                blocked++;
            } else if (r.servedFromCache()) {
                cacheHits++;
            } else if (r.cacheType() != CacheType.SEMANTIC && r.cacheType() != CacheType.EXACT
                    && r.chosenProvider() != null) {
                ok++;
            } else {
                failed++;
            }
        }

        return new ModeSummary(result.mode(), records.size(),
                promptTokens, completionTokens, cost, latency,
                cacheHits, ok, rejected, blocked, failed, fallbacks);
    }

    /**
     * An exact rational {@code numerator / denominator}. Comparisons and rendering use only integer
     * arithmetic, so the same ratio always prints the same bytes. A zero denominator (empty mode)
     * is treated as {@code 0}.
     *
     * @param numerator   non-negative count of the matching events
     * @param denominator non-negative total (0 means "no observations", rendered as zero)
     */
    public record Ratio(int numerator, int denominator) {

        public Ratio {
            if (numerator < 0 || denominator < 0) {
                throw new IllegalArgumentException("ratio components must be non-negative");
            }
        }

        /**
         * Per-mille (parts per thousand) as a truncating integer: {@code numerator * 1000 /
         * denominator}, or {@code 0} when the denominator is 0. Deterministic — no floating point.
         */
        public int perMille() {
            return denominator == 0 ? 0 : (int) ((long) numerator * 1000L / denominator);
        }

        /**
         * Fixed one-decimal percentage string derived solely from {@link #perMille()} (e.g.
         * {@code "40.0%"}, {@code "0.0%"}, {@code "100.0%"}). Pure integer formatting, so two equal
         * ratios render byte-identical strings.
         */
        public String asPercent() {
            int perMille = perMille();           // 0..1000 for proper fractions
            int whole = perMille / 10;           // integer percent
            int tenth = perMille % 10;           // first decimal digit
            return whole + "." + tenth + "%";
        }
    }
}
