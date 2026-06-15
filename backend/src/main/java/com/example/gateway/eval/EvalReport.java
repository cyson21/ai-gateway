package com.example.gateway.eval;

import java.util.List;

/**
 * A mode-comparison report: one {@link ModeSummary} per {@link EvalResult}, preserving the input
 * list's order (which is the mode-comparison order). Built by folding each per-mode result; the
 * fold is pure (no clock, randomness, network, or file IO), so the same inputs always yield an
 * equal report.
 *
 * <p>This record only aggregates — turning it into human-readable text is {@link EvalReportRenderer}'s
 * job, and writing that text anywhere (files, HTTP) is left to a later slice.
 *
 * @param summaries per-mode summaries, in the order the source {@link EvalResult}s were given
 */
public record EvalReport(List<ModeSummary> summaries) {

    public EvalReport {
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
    }

    /**
     * Fold each {@link EvalResult} (one per mode) into a {@link ModeSummary}, preserving order.
     *
     * @param results per-mode results, e.g. from {@code EvalRunner.run(pipeline, modes, set)}
     */
    public static EvalReport of(List<EvalResult> results) {
        if (results == null) {
            throw new IllegalArgumentException("results are required");
        }
        return new EvalReport(results.stream().map(ModeSummary::fold).toList());
    }
}
