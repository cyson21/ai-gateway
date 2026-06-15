package com.example.gateway.api;

/**
 * Comparison modes for the gateway pipeline. Each mode is compared with the same
 * runner and metric format so behavior differences are explicit.
 */
public enum PipelineMode {
    /** Single provider direct, no cache and no fallback. */
    PASSTHROUGH,
    /** Semantic cache only. */
    CACHE_ONLY,
    /** Policy-based routing. */
    ROUTED,
    /** Routing plus fallback chain and retry budget. */
    ROUTED_RESILIENT
}
