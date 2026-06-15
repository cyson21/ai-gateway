package com.example.gateway.eval;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.observability.RequestRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic eval harness: drives a {@link GoldenRequests golden set} through a <b>real</b>
 * {@link GatewayPipeline} and collects the per-request {@link RequestRecord}s.
 *
 * <p>The runner adds no behavior of its own — it calls {@code pipeline.handle(request, mode)} for
 * each golden fixture, in golden-set order, and pairs each emitted record with the fixture's label
 * and meta-class. Determinism (including {@code latencyMs}) is the caller's responsibility: assemble
 * the pipeline with deterministic collaborators and an injected monotonic clock. Aggregating /
 * rendering these results is M5-2.
 */
public final class EvalRunner {

    /** Run the golden set through one mode, collecting one {@link EvalResult.Item} per fixture. */
    public EvalResult run(GatewayPipeline pipeline, PipelineMode mode, List<GoldenRequest> set) {
        if (pipeline == null) {
            throw new IllegalArgumentException("pipeline is required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
        if (set == null) {
            throw new IllegalArgumentException("golden set is required");
        }
        List<EvalResult.Item> items = new ArrayList<>(set.size());
        for (GoldenRequest golden : set) {
            RequestRecord record = pipeline.handle(golden.request(), mode);
            items.add(new EvalResult.Item(golden.label(), golden.expectedClass(), record));
        }
        return new EvalResult(mode, items);
    }

    /** Run the golden set across several modes, yielding one {@link EvalResult} per mode in order. */
    public List<EvalResult> run(GatewayPipeline pipeline, List<PipelineMode> modes, List<GoldenRequest> set) {
        if (modes == null) {
            throw new IllegalArgumentException("modes are required");
        }
        List<EvalResult> results = new ArrayList<>(modes.size());
        for (PipelineMode mode : modes) {
            results.add(run(pipeline, mode, set));
        }
        return results;
    }
}
