package com.example.gateway.quota;

/** Result of the quota stage (rate limit + token/cost budget). */
public enum QuotaOutcome {
    /** Request is within rate limit and budget. */
    ALLOWED,
    /** Rejected by the sliding-window rate limit (maps to HTTP 429). */
    RATE_LIMITED,
    /** Rejected by the token/cost budget (maps to HTTP 402). */
    BUDGET_EXCEEDED
}
