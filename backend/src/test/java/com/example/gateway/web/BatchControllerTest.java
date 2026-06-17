package com.example.gateway.web;

import java.util.List;
import java.util.Map;

import com.example.gateway.auth.ApiKeyAuthFilter;
import com.example.gateway.batch.BatchService;
import com.example.gateway.batch.InMemoryBatchJobStore;
import com.example.gateway.config.GatewayPipelineConfig;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

class BatchControllerTest {

    private final BatchService service =
            new BatchService(new GatewayPipelineConfig().gatewayPipeline(), new InMemoryBatchJobStore());

    private WebTestClient client() {
        return client("tenant-1");
    }

    private WebTestClient client(String tenantId) {
        WebFilter tenantFilter = (exchange, chain) -> {
            exchange.getAttributes().put(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE, tenantId);
            return chain.filter(exchange);
        };
        return WebTestClient.bindToController(new BatchController(service))
                .webFilter(tenantFilter)
                .build();
    }

    @Test
    void batchEndpointQueuesJobAndLocalWorkerEndpointProcessesIt() {
        Map<String, Object> body = Map.of(
                "mode", "ROUTED_RESILIENT",
                "requests", List.of(
                        Map.of("model", "gpt-4o", "messages",
                                List.of(Map.of("role", "user", "content", "first")), "max_tokens", 64),
                        Map.of("model", "fast", "messages",
                                List.of(Map.of("role", "user", "content", "second")), "max_tokens", 64)));

        String jobId = client().post().uri("/v1/batches/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("QUEUED")
                .jsonPath("$.requestCount").isEqualTo(2)
                .returnResult()
                .getResponseBodyContent() == null ? "" : "";

        jobId = service.jobs().get(0).id();

        client().post().uri("/v1/batches/{id}/process", jobId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.completedCount").isEqualTo(2);

        client().get().uri("/v1/batches/{id}", jobId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.results[0].record.tenantId").isEqualTo("tenant-1");
    }

    @Test
    void batchLookupAndProcessingAreTenantScoped() {
        Map<String, Object> body = Map.of(
                "mode", "ROUTED_RESILIENT",
                "requests", List.of(
                        Map.of("model", "gpt-4o", "messages",
                                List.of(Map.of("role", "user", "content", "tenant scoped")), "max_tokens", 64)));

        client().post().uri("/v1/batches/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted();

        String jobId = service.jobs().get(0).id();

        client("tenant-2").get().uri("/v1/batches/{id}", jobId)
                .exchange()
                .expectStatus().isNotFound();

        client("tenant-2").post().uri("/v1/batches/{id}/process", jobId)
                .exchange()
                .expectStatus().isNotFound();
    }
}
