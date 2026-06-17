package com.example.gateway.web;

import java.util.UUID;

import com.example.gateway.api.GatewayResult;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.cache.CacheType;
import com.example.gateway.observability.RequestRecord;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.ToolCall;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Projection of the pipeline's {@link RequestRecord} returned from
 * {@code /v1/chat/completions}. The PASSTHROUGH foundation records routing, token, cost,
 * cache, and latency metadata but not the response text; structured choices/content are
 * delivered by the streaming slice. Fields surface what every request path can populate.
 */
public record ChatCompletionResponse(
        String id,
        String object,
        String tenant,
        String model,
        PipelineMode mode,
        String status,
        java.util.List<Choice> choices,
        UsageView usage,
        long latencyMs,
        long cost,
        CacheType cacheType,
        int fallbackCount) {

    public record Choice(int index, Message message, String finishReason) {
    }

    public record Message(String role, String content, @JsonProperty("tool_calls") java.util.List<ToolCall> toolCalls) {
        public Message(String role, String content) {
            this(role, content, java.util.List.of());
        }

        public Message {
            toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls);
        }
    }

    public record UsageView(int promptTokens, int completionTokens, int totalTokens) {
    }

    public static ChatCompletionResponse from(RequestRecord record) {
        return from(new GatewayResult(record, null));
    }

    public static ChatCompletionResponse from(GatewayResult result) {
        RequestRecord record = result.record();
        CompletionResponse response = result.response();
        java.util.List<Choice> choices = response == null
                ? java.util.List.of()
                : java.util.List.of(new Choice(0,
                        new Message("assistant", response.content(), response.toolCalls()),
                        response.hasToolCalls() ? "tool_calls" : "stop"));
        return new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID(),
                "chat.completion",
                record.tenantId(),
                record.chosenModel(),
                record.mode(),
                record.message(),
                choices,
                new UsageView(record.promptTokens(), record.completionTokens(), record.totalTokens()),
                record.latencyMs(),
                record.cost(),
                record.cacheType(),
                record.fallbackCount());
    }
}
