package com.example.gateway.eval;

import com.example.gateway.api.PipelineMode;
import com.example.gateway.observability.RequestRecord;

import java.util.List;

/**
 * Result of running one golden set through one {@link PipelineMode}.
 *
 * <p>This slice (M5-1) collects only — each {@link Item} pairs a golden fixture's label and
 * meta-class with the {@link RequestRecord} the real pipeline emitted, preserving golden-set
 * order. Folding these per-mode results into a comparison report is M5-2's job; the shape is
 * deliberately {@code (mode, List<Item>)} so that aggregation is straightforward.
 *
 * @param mode  the mode the golden set was run under
 * @param items per-golden-request results, in golden-set order
 */
public record EvalResult(PipelineMode mode, List<Item> items) {

    public EvalResult {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Convenience: the collected records in order, dropping labels. */
    public List<RequestRecord> records() {
        return items.stream().map(Item::record).toList();
    }

    /**
     * One golden request's outcome: its label and meta-class alongside the emitted record.
     *
     * @param label         the golden fixture label
     * @param expectedClass the fixture's coarse meta-classification
     * @param record        the {@link RequestRecord} the pipeline produced for it
     */
    public record Item(String label, GoldenRequest.ExpectedClass expectedClass, RequestRecord record) {
    }
}
