package com.example.gateway.batch;

import java.util.List;

import com.example.gateway.api.GatewayResult;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.provider.CompletionRequest;

public record BatchJob(
        String id,
        String tenantId,
        PipelineMode mode,
        BatchStatus status,
        List<CompletionRequest> requests,
        List<GatewayResult> results,
        String message) {

    public BatchJob {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (mode == null) {
            mode = PipelineMode.ROUTED_RESILIENT;
        }
        if (status == null) {
            status = BatchStatus.QUEUED;
        }
        requests = requests == null ? List.of() : List.copyOf(requests);
        results = results == null ? List.of() : List.copyOf(results);
    }

    public int requestCount() {
        return requests.size();
    }

    public int completedCount() {
        return results.size();
    }
}
