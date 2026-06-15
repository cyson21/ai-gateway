package com.example.gateway.observability;

import com.example.gateway.api.PipelineMode;
import com.example.gateway.cache.CacheType;
import com.example.gateway.guardrail.GuardrailResult;
import com.example.gateway.quota.QuotaOutcome;

/**
 * Per-request observability record. Every path through the pipeline produces a fully
 * populated record so token, latency, cost, cache, fallback, and guardrail behavior
 * can be compared per tenant.
 */
public record RequestRecord(
        String tenantId,
        String alias,
        PipelineMode mode,
        String chosenProvider,
        String chosenModel,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        long cost,
        CacheType cacheType,
        int fallbackCount,
        GuardrailResult guardrailResult,
        QuotaOutcome budgetOutcome,
        String message) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean servedFromCache() {
        return cacheType == CacheType.EXACT || cacheType == CacheType.SEMANTIC;
    }
}
