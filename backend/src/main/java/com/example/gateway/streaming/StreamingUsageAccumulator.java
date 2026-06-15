package com.example.gateway.streaming;

import java.util.Optional;

import com.example.gateway.provider.Usage;
import com.example.gateway.streaming.ChatCompletionChunk.Delta;
import com.example.gateway.streaming.StreamEvent.ChunkEvent;
import com.example.gateway.streaming.StreamEvent.Done;

/**
 * Folds an OpenAI-compatible {@link StreamEvent} sequence into a final {@link Usage}, guaranteeing
 * the streaming path reconciles to the <b>same</b> {@code Usage} the non-streaming (buffered) path
 * reports for the same response.
 *
 * <p><b>Path-match invariant (the keystone).</b> The non-streaming path's
 * {@code CompletionResponse.usage()} is the ground truth. A streaming chunk count is <i>not</i>
 * the same unit as the provider's {@code completionTokens} (Streaming-1 tokenizes on whitespace
 * for SSE framing, which need not equal the provider's token accounting). So this accumulator
 * cleanly separates two concerns:
 * <ul>
 *   <li><b>Incremental progress (observation only):</b> {@link #observedCompletionChunks()} counts
 *       the content deltas seen so far. It rises monotonically as chunks flow — a UI/telemetry
 *       signal, never an accounting figure.</li>
 *   <li><b>Final authoritative usage (accounting):</b> {@link #finalUsage()} returns the
 *       authoritative {@link Usage} carried on the terminal chunk (the OpenAI {@code include_usage}
 *       slot the projector seeds with the original {@code response.usage()}). Because that value is
 *       the original response usage verbatim, the streamed final usage equals the buffered usage
 *       exactly — prompt, completion, and total tokens all match.</li>
 * </ul>
 *
 * <p>The fold is deterministic: no clock, no randomness, no I/O. The same response → same chunks
 * → same final usage on every run.
 *
 * <p><b>Terminal handling.</b> Usage is confirmed when the chunk carrying authoritative usage is
 * accepted (the {@code finish_reason} terminal chunk); the trailing {@link Done} sentinel is a
 * no-op for accounting. If the stream ends without ever delivering an authoritative-usage chunk
 * (truncated stream), usage stays <b>unconfirmed</b>: {@link #isFinalized()} is {@code false} and
 * {@link #finalUsage()} throws. Observed progress remains available as the partial signal.
 */
public final class StreamingUsageAccumulator {

    /**
     * Prompt tokens are known at request time (the provider reports them on the response); a caller
     * may seed them so partial/unconfirmed usage can still report the prompt side. When the
     * authoritative usage arrives it supersedes any seed.
     */
    private final int seedPromptTokens;

    private int observedCompletionChunks;
    private Usage authoritative;

    /** No prompt seed; prompt tokens are taken solely from the authoritative terminal usage. */
    public StreamingUsageAccumulator() {
        this(0);
    }

    /**
     * @param seedPromptTokens prompt tokens known up front, surfaced via {@link #partialUsage()}
     *                         before the authoritative usage arrives; must be {@code >= 0}
     */
    public StreamingUsageAccumulator(int seedPromptTokens) {
        if (seedPromptTokens < 0) {
            throw new IllegalArgumentException("seedPromptTokens must be >= 0");
        }
        this.seedPromptTokens = seedPromptTokens;
    }

    /**
     * Accepts one event in stream order. Content deltas advance observed progress; the terminal
     * chunk's authoritative usage finalizes accounting; {@link Done} is a no-op.
     *
     * @return this accumulator, for chaining/folding
     */
    public StreamingUsageAccumulator accept(StreamEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        if (event instanceof ChunkEvent chunkEvent) {
            ChatCompletionChunk chunk = chunkEvent.chunk();
            Delta delta = chunk.delta();
            if (delta.content() != null) {
                observedCompletionChunks++;
            }
            if (chunk.usage() != null) {
                authoritative = chunk.usage();
            }
        }
        // Done.INSTANCE: termination sentinel, nothing to accumulate.
        return this;
    }

    /** Folds an entire event sequence in order. Equivalent to repeated {@link #accept}. */
    public StreamingUsageAccumulator acceptAll(Iterable<StreamEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("events are required");
        }
        for (StreamEvent event : events) {
            accept(event);
        }
        return this;
    }

    /**
     * Folds {@code events} into a final {@link Usage} in one call. Convenience over
     * {@code new StreamingUsageAccumulator().acceptAll(events).finalUsage()}.
     *
     * @throws IllegalStateException if the stream carried no authoritative usage
     */
    public static Usage fold(Iterable<StreamEvent> events) {
        return new StreamingUsageAccumulator().acceptAll(events).finalUsage();
    }

    /** Count of content deltas observed so far — monotonic, observation-only (never accounting). */
    public int observedCompletionChunks() {
        return observedCompletionChunks;
    }

    /** Whether an authoritative usage has been accepted (stream reached its terminal usage chunk). */
    public boolean isFinalized() {
        return authoritative != null;
    }

    /**
     * The authoritative final usage — identical to the buffered path's {@code response.usage()}.
     *
     * @throws IllegalStateException if no authoritative usage was seen (truncated stream)
     */
    public Usage finalUsage() {
        if (authoritative == null) {
            throw new IllegalStateException(
                    "stream carried no authoritative usage; usage is unconfirmed (truncated stream)");
        }
        return authoritative;
    }

    /**
     * Best-effort usage at the current point in the fold. Once finalized, this is the authoritative
     * usage. Before then it is a partial view: the seeded prompt tokens plus observed content chunks
     * as a <i>provisional</i> completion-token estimate (not authoritative — for progress only).
     */
    public Usage partialUsage() {
        if (authoritative != null) {
            return authoritative;
        }
        return new Usage(seedPromptTokens, observedCompletionChunks);
    }

    /** The final usage if finalized, otherwise empty — non-throwing variant of {@link #finalUsage()}. */
    public Optional<Usage> finalUsageIfPresent() {
        return Optional.ofNullable(authoritative);
    }
}
