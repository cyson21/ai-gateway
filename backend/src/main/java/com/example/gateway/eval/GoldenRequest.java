package com.example.gateway.eval;

import com.example.gateway.provider.CompletionRequest;

/**
 * One entry in the deterministic golden request set used by the eval harness.
 *
 * <p>The entry is just a labelled, plain {@link CompletionRequest} plus a coarse
 * {@code expectedClass} meta-hint describing which pipeline path the fixture is meant to
 * exercise. The request itself carries no routing magic — path divergence (cache hit,
 * input guardrail block, fallback, ...) is produced by the collaborator fakes the
 * {@link EvalRunner} is given, not by the request content. The hint exists so M5-2 can
 * group/aggregate records without re-deriving intent.
 *
 * @param label         stable identifier for the fixture (deterministic, unique within a set)
 * @param request       the plain completion request to push through {@code pipeline.handle}
 * @param expectedClass coarse meta-classification of the path this fixture targets
 */
public record GoldenRequest(String label, CompletionRequest request, ExpectedClass expectedClass) {

    public GoldenRequest {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (expectedClass == null) {
            expectedClass = ExpectedClass.OK;
        }
    }

    /** Coarse classification of the path a golden fixture is designed to exercise. */
    public enum ExpectedClass {
        /** Normal provider call that succeeds. */
        OK,
        /** Duplicate of an earlier request, intended to drive a cache hit. */
        CACHE_HIT,
        /** Request whose content trips the input guardrail. */
        GUARDRAIL_BLOCKED,
        /** Request whose primary candidate fails, driving a fallback / failure. */
        FALLBACK
    }
}
