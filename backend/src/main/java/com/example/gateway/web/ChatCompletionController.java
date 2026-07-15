package com.example.gateway.web;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.api.GatewayResult;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.auth.ApiKeyAuthFilter;
import com.example.gateway.observability.RequestLogStore;
import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.quota.QuotaOutcome;
import com.example.gateway.streaming.StreamChunkProjector;
import com.example.gateway.streaming.StreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenAI-compatible chat completions endpoint. Authentication is handled upstream by
 * {@link ApiKeyAuthFilter}, which publishes the resolved tenant id as an exchange
 * attribute; this handler consumes it rather than re-authenticating. Requests default to
 * {@link PipelineMode#ROUTED_RESILIENT} and may opt into another mode with X-Gateway-Mode.
 */
@RestController
public class ChatCompletionController {

    private final GatewayPipeline pipeline;
    private final RequestLogStore requestLogStore;
    private final StreamChunkProjector projector;
    private final ObjectMapper objectMapper;

    public ChatCompletionController(GatewayPipeline pipeline) {
        this(pipeline, new com.example.gateway.observability.InMemoryRequestLogStore(), new ObjectMapper());
    }

    @Autowired
    public ChatCompletionController(GatewayPipeline pipeline, RequestLogStore requestLogStore, ObjectMapper objectMapper) {
        this.pipeline = pipeline;
        this.requestLogStore = requestLogStore;
        this.projector = new StreamChunkProjector();
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @PostMapping(path = "/v1/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Mono<ResponseEntity<?>> create(@RequestBody ChatCompletionRequest body,
                                          @RequestHeader(name = "X-Gateway-Mode", required = false) String modeHeader,
                                          ServerWebExchange exchange) {
        String tenantId = exchange.getAttribute(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE);
        if (tenantId == null || tenantId.isBlank()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        CompletionRequest request = body.toCompletionRequest(tenantId);
        PipelineMode mode = parseMode(modeHeader);
        GatewayResult result = pipeline.execute(request, mode);
        requestLogStore.append(result.record());
        HttpStatus status = statusFor(result);
        if (request.stream() && result.hasResponse() && status.is2xxSuccessful()) {
            return Mono.just(ResponseEntity.status(status)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream(result)));
        }
        return Mono.just(ResponseEntity.status(status).body(ChatCompletionResponse.from(result)));
    }

    /** A malformed body (blank alias, no messages, non-positive tokens) is a 400, not a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String onInvalidRequest(IllegalArgumentException e) {
        return e.getMessage();
    }

    private PipelineMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return PipelineMode.ROUTED_RESILIENT;
        }
        try {
            return PipelineMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("unsupported X-Gateway-Mode");
        }
    }

    private HttpStatus statusFor(GatewayResult result) {
        if (result.record().budgetOutcome() == QuotaOutcome.RATE_LIMITED) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (result.record().budgetOutcome() == QuotaOutcome.BUDGET_EXCEEDED) {
            return HttpStatus.PAYMENT_REQUIRED;
        }
        if (!result.hasResponse() && result.record().chosenProvider() == null
                && result.record().message() != null
                && result.record().message().startsWith("all candidates failed")) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.OK;
    }

    private Flux<String> stream(GatewayResult result) {
        String id = "chatcmpl-" + java.util.UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000L;
        return Flux.fromIterable(projector.project(result.response(), id, created))
                .map(this::ssePayload);
    }

    private String ssePayload(StreamEvent event) {
        try {
            if (event instanceof StreamEvent.Done) {
                return StreamEvent.Done.SENTINEL;
            }
            StreamEvent.ChunkEvent chunk = (StreamEvent.ChunkEvent) event;
            return objectMapper.writeValueAsString(chunk.chunk());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize stream event", e);
        }
    }
}
