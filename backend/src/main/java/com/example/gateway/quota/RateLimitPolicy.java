package com.example.gateway.quota;

/**
 * A tenant's sliding-window rate limit: at most {@code maxRequests} within any
 * {@code windowSeconds}-long window. Mirrors the {@code rate_limits} row
 * (tenant_id, window_seconds, max_requests) and enforces the same positivity check.
 */
public record RateLimitPolicy(int windowSeconds, int maxRequests) {

    public RateLimitPolicy {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive");
        }
    }

    /** Window length in milliseconds, the unit used by {@link RateLimitStore}. */
    public long windowMillis() {
        return windowSeconds * 1000L;
    }
}
