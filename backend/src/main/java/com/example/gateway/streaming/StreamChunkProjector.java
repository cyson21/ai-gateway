package com.example.gateway.streaming;

import java.util.ArrayList;
import java.util.List;

import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.streaming.ChatCompletionChunk.Delta;
import com.example.gateway.streaming.StreamEvent.ChunkEvent;
import com.example.gateway.streaming.StreamEvent.Done;

/**
 * Deterministically projects a fully materialized {@link CompletionResponse} into the
 * OpenAI-compatible {@code chat.completion.chunk} stream shape.
 *
 * <p>The provider is currently synchronous one-shot ({@code complete}); this component models
 * "split an already-complete content into the SSE chunk sequence a streaming client expects".
 * It is pure: no clock, no randomness, no I/O. The {@code id}/{@code created} metadata is
 * injected per call, so identical input + identical metadata yields byte-identical output.
 *
 * <p>Emitted sequence for content with N tokens:
 * <ol>
 *   <li>role-opening chunk: {@code delta={role:"assistant"}}, no finish reason;</li>
 *   <li>N content chunks: {@code delta={content:<token>}}, no finish reason;</li>
 *   <li>terminal chunk: empty delta, {@code finish_reason="stop"};</li>
 *   <li>{@link Done} sentinel ({@code data: [DONE]}).</li>
 * </ol>
 * Empty content yields zero content chunks but still the role, terminal, and sentinel events.
 *
 * <p><b>Recombination invariant:</b> concatenating the {@code content} of every content delta,
 * in order, reproduces the original {@link CompletionResponse#content()} exactly — including
 * leading and interior whitespace.
 */
public final class StreamChunkProjector {

    /** Role carried by the opening delta, per OpenAI convention. */
    public static final String ASSISTANT_ROLE = "assistant";

    /** {@code finish_reason} for a normal end-of-completion. */
    public static final String FINISH_STOP = "stop";

    /**
     * Projects {@code response} into the ordered event sequence.
     *
     * @param response the completed response to stream
     * @param id       shared chunk id (e.g. {@code "chatcmpl-..."}); injected, not generated
     * @param created  shared {@code created} epoch-seconds metadata; injected, not from a clock
     */
    public List<StreamEvent> project(CompletionResponse response, String id, long created) {
        if (response == null) {
            throw new IllegalArgumentException("response is required");
        }
        String model = response.model();
        List<StreamEvent> events = new ArrayList<>();

        events.add(chunk(id, created, model, Delta.role(ASSISTANT_ROLE), null, null));
        for (String token : tokenize(response.content())) {
            events.add(chunk(id, created, model, Delta.content(token), null, null));
        }
        // Terminal chunk carries the authoritative usage (OpenAI include_usage convention): it is the
        // original response.usage(), the accounting ground truth a usage accumulator confirms against.
        events.add(chunk(id, created, model, Delta.empty(), FINISH_STOP, response.usage()));
        events.add(Done.INSTANCE);

        return List.copyOf(events);
    }

    private static ChunkEvent chunk(
            String id, long created, String model, Delta delta, String finishReason, com.example.gateway.provider.Usage usage) {
        return new ChunkEvent(
                new ChatCompletionChunk(id, ChatCompletionChunk.OBJECT, created, model, delta, finishReason, usage));
    }

    /**
     * Deterministic word-boundary split. Each token keeps the run of whitespace that precedes
     * it, so concatenating the tokens reproduces the input exactly. Empty input yields no
     * tokens. A run of leading whitespace attaches to the first non-space token; a string of
     * only whitespace becomes a single token holding that whitespace.
     */
    static List<String> tokenize(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean seenNonSpace = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == ' ') {
                // A space starts a new token only once the current token already holds a word.
                if (seenNonSpace) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    seenNonSpace = false;
                }
                current.append(c);
            } else {
                current.append(c);
                seenNonSpace = true;
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }
}
