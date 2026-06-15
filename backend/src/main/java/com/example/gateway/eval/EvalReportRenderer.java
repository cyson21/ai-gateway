package com.example.gateway.eval;

/**
 * Renders an {@link EvalReport} into a deterministic Markdown table — one row per mode, in report
 * order. Pure: no clock, randomness, network, or file IO, and no floating point. The same report
 * always produces a byte-identical {@link String}; nothing is written anywhere (file/report output
 * paths are a later slice's concern).
 *
 * <p>Columns are fixed: {@code mode | requests | ok | cache hits | rejected | blocked | failed |
 * fallbacks | total cost | total tokens | cache hit rate | avg latency (ms)}. Header and separator
 * rows are constant, and every value is produced by integer formatting, so column structure is
 * stable across calls.
 */
public final class EvalReportRenderer {

    private static final String HEADER =
            "| mode | requests | ok | cache hits | rejected | blocked | failed | fallbacks "
                    + "| total cost | total tokens | cache hit rate | avg latency (ms) |";
    private static final String SEPARATOR =
            "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |";

    /** Render the report as a Markdown table. A report with no modes yields header + separator only. */
    public String render(EvalReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report is required");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        sb.append(SEPARATOR);
        for (ModeSummary summary : report.summaries()) {
            sb.append('\n').append(renderRow(summary));
        }
        return sb.toString();
    }

    private static String renderRow(ModeSummary s) {
        return "| " + s.mode().name()
                + " | " + s.requestCount()
                + " | " + s.okCount()
                + " | " + s.cacheHitCount()
                + " | " + s.rejectedCount()
                + " | " + s.blockedCount()
                + " | " + s.failedCount()
                + " | " + s.fallbackCount()
                + " | " + s.totalCost()
                + " | " + s.totalTokens()
                + " | " + s.cacheHitRate().asPercent()
                + " | " + s.averageLatencyMs()
                + " |";
    }
}
