package com.example.gateway.router;

/** Strategy used to pick a concrete provider/model for a model alias. */
public enum RoutingStrategy {
    /** Alias maps to exactly one declared target. */
    FIXED,
    /** Lowest declared cost among healthy candidates; ties broken deterministically. */
    LEAST_COST,
    /** Lowest declared latency among healthy candidates; ties broken deterministically. */
    LEAST_LATENCY
}
