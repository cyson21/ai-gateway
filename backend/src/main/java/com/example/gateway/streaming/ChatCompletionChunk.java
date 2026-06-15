package com.example.gateway.streaming;

import com.example.gateway.provider.Usage;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One {@code chat.completion.chunk} event in an OpenAI-compatible stream.
 *
 * <p>All chunks of a single response share the same {@code id}, {@code model}, and
 * {@code created} metadata. The metadata is injected (never sourced from a clock or RNG) so
 * the projection stays deterministic: identical input + identical injected metadata yields
 * identical output.
 *
 * <p>The {@link Delta} mirrors OpenAI's incremental message shape: the first chunk carries
 * {@code role="assistant"} with no content, subsequent chunks carry a content fragment, and
 * the terminal chunk carries {@code finishReason="stop"} with no role or content.
 *
 * <p>{@code usage} is {@code null} on every chunk except the terminal one, mirroring OpenAI's
 * {@code stream_options.include_usage} convention where the final chunk carries the authoritative
 * token totals. This is the accounting ground truth a streaming usage accumulator confirms against
 * (Streaming-2): the value equals the original {@code CompletionResponse.usage()} the projection
 * was built from, so streaming and non-streaming paths reconcile to the same {@link Usage}.
 */
public record ChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        Delta delta,
        @JsonProperty("finish_reason")
        String finishReason,
        Usage usage) {

    /** OpenAI's literal {@code object} value for streamed completion chunks. */
    public static final String OBJECT = "chat.completion.chunk";

    public ChatCompletionChunk {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (delta == null) {
            throw new IllegalArgumentException("delta is required");
        }
        object = OBJECT;
    }

    /** Back-compatible factory for non-terminal chunks, which never carry usage. */
    public ChatCompletionChunk(String id, String object, long created, String model, Delta delta, String finishReason) {
        this(id, object, created, model, delta, finishReason, null);
    }

    /**
     * Incremental message payload. Any field may be {@code null}: the role-opening chunk has
     * only {@code role}, content chunks have only {@code content}, and the terminal chunk has
     * neither (its signal is the enclosing chunk's {@code finishReason}).
     */
    public record Delta(String role, String content) {

        public static Delta role(String role) {
            return new Delta(role, null);
        }

        public static Delta content(String content) {
            return new Delta(null, content);
        }

        public static Delta empty() {
            return new Delta(null, null);
        }
    }
}
