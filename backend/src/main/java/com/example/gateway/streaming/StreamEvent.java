package com.example.gateway.streaming;

/**
 * One element of an OpenAI-compatible SSE stream. The projection emits an ordered list of
 * these: zero or more {@link ChunkEvent}s followed by exactly one terminal {@link Done}.
 *
 * <p>This slice is a pure, deterministic transformation: no clock, no randomness, no I/O.
 * The reactive {@code Flux}/controller wiring and SSE transport are out of scope.
 */
public sealed interface StreamEvent permits StreamEvent.ChunkEvent, StreamEvent.Done {

    /** Wraps a {@link ChatCompletionChunk} payload (the {@code data:} of one SSE line). */
    record ChunkEvent(ChatCompletionChunk chunk) implements StreamEvent {
        public ChunkEvent {
            if (chunk == null) {
                throw new IllegalArgumentException("chunk is required");
            }
        }
    }

    /**
     * The {@code data: [DONE]} stream-termination sentinel. A singleton because it carries
     * no payload; {@link #INSTANCE} is the only value.
     */
    record Done() implements StreamEvent {
        public static final Done INSTANCE = new Done();

        /** OpenAI's literal sentinel payload following {@code data: }. */
        public static final String SENTINEL = "[DONE]";
    }
}
