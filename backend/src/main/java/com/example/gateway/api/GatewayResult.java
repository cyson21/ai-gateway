package com.example.gateway.api;

import com.example.gateway.observability.RequestRecord;
import com.example.gateway.provider.CompletionResponse;

/**
 * Full result of one gateway pipeline execution. {@link RequestRecord} is always present for
 * observability; {@link CompletionResponse} is present only when the request produced a provider or
 * cache response that can be returned or streamed to the caller.
 */
public record GatewayResult(RequestRecord record, CompletionResponse response) {

    public GatewayResult {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
    }

    public boolean hasResponse() {
        return response != null;
    }
}
