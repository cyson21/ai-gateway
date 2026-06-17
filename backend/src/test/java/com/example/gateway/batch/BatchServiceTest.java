package com.example.gateway.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.config.GatewayPipelineConfig;
import com.example.gateway.provider.CompletionRequest;

import java.util.List;

import org.junit.jupiter.api.Test;

class BatchServiceTest {

    @Test
    void batchJobIsQueuedThenProcessedIntoPerRequestResults() {
        GatewayPipeline pipeline = new GatewayPipelineConfig().gatewayPipeline();
        BatchService service = new BatchService(pipeline, new InMemoryBatchJobStore());
        List<CompletionRequest> requests = List.of(
                new CompletionRequest("tenant-1", "gpt-4o", "first request", 64, false),
                new CompletionRequest("tenant-1", "fast", "second request", 64, false));

        BatchJob queued = service.submit("tenant-1", PipelineMode.ROUTED_RESILIENT, requests);

        assertThat(queued.status()).isEqualTo(BatchStatus.QUEUED);
        assertThat(queued.requestCount()).isEqualTo(2);
        assertThat(queued.completedCount()).isZero();

        BatchJob completed = service.process(queued.id());

        assertThat(completed.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(completed.completedCount()).isEqualTo(2);
        assertThat(completed.results()).hasSize(2);
        assertThat(completed.results()).allSatisfy(result ->
                assertThat(result.record().tenantId()).isEqualTo("tenant-1"));
    }
}
