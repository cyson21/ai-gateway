package com.example.gateway.batch;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.api.GatewayResult;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.provider.CompletionRequest;

public final class BatchService {

    private final GatewayPipeline pipeline;
    private final BatchJobStore store;

    public BatchService(GatewayPipeline pipeline, BatchJobStore store) {
        if (pipeline == null || store == null) {
            throw new IllegalArgumentException("pipeline and store are required");
        }
        this.pipeline = pipeline;
        this.store = store;
    }

    public BatchJob submit(String tenantId, PipelineMode mode, List<CompletionRequest> requests) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("requests are required");
        }
        for (CompletionRequest request : requests) {
            if (request == null) {
                throw new IllegalArgumentException("requests must not contain null");
            }
            if (!tenantId.equals(request.tenantId())) {
                throw new IllegalArgumentException("all requests must belong to the batch tenant");
            }
        }
        BatchJob job = new BatchJob(
                UUID.randomUUID().toString(),
                tenantId,
                mode == null ? PipelineMode.ROUTED_RESILIENT : mode,
                BatchStatus.QUEUED,
                requests,
                List.of(),
                null);
        return store.save(job);
    }

    public BatchJob process(String id) {
        BatchJob current = get(id);
        if (current.status() == BatchStatus.COMPLETED) {
            return current;
        }
        store.save(copy(current, BatchStatus.RUNNING, current.results(), null));
        List<GatewayResult> results = new ArrayList<>(current.requests().size());
        try {
            for (CompletionRequest request : current.requests()) {
                results.add(pipeline.execute(request, current.mode()));
            }
            return store.save(copy(current, BatchStatus.COMPLETED, results, null));
        } catch (RuntimeException e) {
            store.save(copy(current, BatchStatus.FAILED, results, e.getMessage()));
            throw e;
        }
    }

    public BatchJob get(String id) {
        return store.find(id).orElseThrow(() -> new NoSuchElementException("batch not found: " + id));
    }

    public List<BatchJob> jobs() {
        return store.all();
    }

    private static BatchJob copy(BatchJob job, BatchStatus status, List<GatewayResult> results, String message) {
        return new BatchJob(job.id(), job.tenantId(), job.mode(), status, job.requests(), results, message);
    }
}
