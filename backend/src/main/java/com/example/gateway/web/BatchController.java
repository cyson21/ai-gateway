package com.example.gateway.web;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

import com.example.gateway.api.GatewayResult;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.auth.ApiKeyAuthFilter;
import com.example.gateway.batch.BatchJob;
import com.example.gateway.batch.BatchService;
import com.example.gateway.batch.BatchStatus;
import com.example.gateway.provider.CompletionRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    @PostMapping(path = "/v1/batches/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<BatchJobView>> create(@RequestBody BatchCreateRequest body, ServerWebExchange exchange) {
        String tenantId = tenant(exchange);
        if (tenantId == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        if (body == null) {
            throw new IllegalArgumentException("body is required");
        }
        List<CompletionRequest> requests = body.requests().stream()
                .map(request -> request.toCompletionRequest(tenantId))
                .toList();
        BatchJob job = service.submit(tenantId, parseMode(body.mode()), requests);
        return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).body(BatchJobView.from(job)));
    }

    @PostMapping(path = "/v1/batches/{id}/process", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<BatchJobView>> process(@PathVariable String id, ServerWebExchange exchange) {
        String tenantId = tenant(exchange);
        if (tenantId == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        assertTenant(service.get(id), tenantId);
        return Mono.just(ResponseEntity.ok(BatchJobView.from(service.process(id))));
    }

    @GetMapping(path = "/v1/batches/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<BatchJobView>> get(@PathVariable String id, ServerWebExchange exchange) {
        String tenantId = tenant(exchange);
        if (tenantId == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        BatchJob job = service.get(id);
        assertTenant(job, tenantId);
        return Mono.just(ResponseEntity.ok(BatchJobView.from(job)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String onInvalidRequest(IllegalArgumentException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String onNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    private static String tenant(ServerWebExchange exchange) {
        String tenantId = exchange.getAttribute(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE);
        return tenantId == null || tenantId.isBlank() ? null : tenantId;
    }

    private static PipelineMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return PipelineMode.ROUTED_RESILIENT;
        }
        return PipelineMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    private static void assertTenant(BatchJob job, String tenantId) {
        if (!tenantId.equals(job.tenantId())) {
            throw new NoSuchElementException("batch not found: " + job.id());
        }
    }

    public record BatchCreateRequest(
            @JsonProperty("mode") String mode,
            @JsonProperty("requests") List<ChatCompletionRequest> requests) {

        public BatchCreateRequest {
            if (requests == null || requests.isEmpty()) {
                throw new IllegalArgumentException("requests are required");
            }
        }
    }

    public record BatchJobView(
            String id,
            String tenantId,
            PipelineMode mode,
            BatchStatus status,
            int requestCount,
            int completedCount,
            List<GatewayResult> results,
            String message) {

        static BatchJobView from(BatchJob job) {
            return new BatchJobView(
                    job.id(),
                    job.tenantId(),
                    job.mode(),
                    job.status(),
                    job.requestCount(),
                    job.completedCount(),
                    job.results(),
                    job.message());
        }
    }
}
