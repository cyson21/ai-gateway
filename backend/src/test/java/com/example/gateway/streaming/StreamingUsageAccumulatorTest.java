package com.example.gateway.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.Usage;
import com.example.gateway.streaming.ChatCompletionChunk.Delta;
import com.example.gateway.streaming.StreamEvent.ChunkEvent;
import com.example.gateway.streaming.StreamEvent.Done;

import org.junit.jupiter.api.Test;

/**
 * Streaming-2: folding the Streaming-1 chunk sequence into a final {@link Usage} that matches the
 * non-streaming (buffered) path exactly. The keystone is the <b>path-match invariant</b>: for any
 * response, projecting it to chunks then folding yields the original {@code response.usage()}
 * (prompt, completion, and total tokens all equal). Incremental progress is observation-only; the
 * authoritative usage rides the terminal chunk (OpenAI {@code include_usage} convention).
 */
class StreamingUsageAccumulatorTest {

    private static final String ID = "chatcmpl-fixed-002";
    private static final long CREATED = 1_700_000_000L;

    private final StreamChunkProjector projector = new StreamChunkProjector();

    private CompletionResponse response(String content, Usage usage) {
        return new CompletionResponse(content, "gpt-4o", usage);
    }

    @Test
    void pathMatchInvariant_foldedUsageEqualsOriginalResponseUsage() {
        Usage original = new Usage(11, 23);
        CompletionResponse response = response("Hello there general kenobi you are a bold one", original);

        List<StreamEvent> events = projector.project(response, ID, CREATED);
        Usage folded = StreamingUsageAccumulator.fold(events);

        assertThat(folded).isEqualTo(original);
        assertThat(folded.promptTokens()).isEqualTo(11);
        assertThat(folded.completionTokens()).isEqualTo(23);
        assertThat(folded.totalTokens()).isEqualTo(34);
    }

    @Test
    void pathMatchInvariant_holdsAcrossManyDistinctResponses() {
        // Vary content length and usage; folded final must always equal the authoritative usage,
        // regardless of how many SSE content chunks the whitespace tokenizer produced.
        Object[][] cases = {
                {"a", new Usage(1, 1)},
                {"a b c d e", new Usage(7, 5)},
                {"  leading and  doubled  gaps trailing ", new Usage(50, 9)},
                {"single", new Usage(0, 0)},
                {"x y z", new Usage(1000, 4)},
        };
        for (Object[] c : cases) {
            CompletionResponse response = response((String) c[0], (Usage) c[1]);
            List<StreamEvent> events = projector.project(response, ID, CREATED);

            assertThat(StreamingUsageAccumulator.fold(events))
                    .as("content=%s", c[0])
                    .isEqualTo((Usage) c[1]);
        }
    }

    @Test
    void incrementalProgress_isMonotonicAndConvergesToContentChunkCount() {
        CompletionResponse response = response("one two three four", new Usage(3, 9));
        List<StreamEvent> events = projector.project(response, ID, CREATED);

        StreamingUsageAccumulator acc = new StreamingUsageAccumulator();
        int previous = 0;
        boolean finalizedSeen = false;
        for (StreamEvent event : events) {
            acc.accept(event);
            // Observed progress never decreases.
            assertThat(acc.observedCompletionChunks()).isGreaterThanOrEqualTo(previous);
            previous = acc.observedCompletionChunks();
            if (acc.isFinalized()) {
                finalizedSeen = true;
            }
        }
        // 4 whitespace-split content chunks observed.
        assertThat(acc.observedCompletionChunks()).isEqualTo(4);
        // Finalized by stream end, and final accounting usage is the authoritative value, not the
        // observed chunk count (9 != 4) — progress and accounting are deliberately separate.
        assertThat(finalizedSeen).isTrue();
        assertThat(acc.isFinalized()).isTrue();
        assertThat(acc.finalUsage()).isEqualTo(new Usage(3, 9));
        assertThat(acc.finalUsage().completionTokens()).isNotEqualTo(acc.observedCompletionChunks());
    }

    @Test
    void emptyContent_zeroChunks_foldedUsageStillEqualsAuthoritative() {
        // No content chunks, but the terminal chunk still carries authoritative usage.
        Usage original = new Usage(8, 0);
        CompletionResponse response = response("", original);
        List<StreamEvent> events = projector.project(response, ID, CREATED);

        StreamingUsageAccumulator acc = new StreamingUsageAccumulator().acceptAll(events);

        assertThat(acc.observedCompletionChunks()).isZero();
        assertThat(acc.isFinalized()).isTrue();
        assertThat(acc.finalUsage()).isEqualTo(original);
    }

    @Test
    void emptyContent_withNonZeroCompletionTokens_authoritativeWins() {
        // Provider may report completion tokens even with empty rendered content; authoritative
        // value (not the zero chunk count) is the accounting truth.
        Usage original = new Usage(8, 5);
        List<StreamEvent> events = projector.project(response("", original), ID, CREATED);

        assertThat(StreamingUsageAccumulator.fold(events)).isEqualTo(original);
    }

    @Test
    void terminalConfirms_doneSentinelIsNoOp() {
        List<StreamEvent> events = projector.project(response("a b", new Usage(2, 3)), ID, CREATED);

        StreamingUsageAccumulator acc = new StreamingUsageAccumulator();
        // Feed everything except the trailing Done sentinel: usage is already confirmed.
        for (StreamEvent event : events) {
            if (event instanceof Done) {
                continue;
            }
            acc.accept(event);
        }
        assertThat(acc.isFinalized()).isTrue();
        Usage beforeDone = acc.finalUsage();

        // Done is a no-op for accounting.
        acc.accept(Done.INSTANCE);
        assertThat(acc.finalUsage()).isEqualTo(beforeDone);
    }

    @Test
    void truncatedStream_noAuthoritativeUsage_staysUnfinalizedAndThrows() {
        // Only the role chunk and a content chunk — terminal usage chunk never arrives.
        List<StreamEvent> partial = new ArrayList<>();
        partial.add(new ChunkEvent(
                new ChatCompletionChunk(ID, ChatCompletionChunk.OBJECT, CREATED, "gpt-4o", Delta.role("assistant"), null)));
        partial.add(new ChunkEvent(
                new ChatCompletionChunk(ID, ChatCompletionChunk.OBJECT, CREATED, "gpt-4o", Delta.content("hi"), null)));

        StreamingUsageAccumulator acc = new StreamingUsageAccumulator(7).acceptAll(partial);

        assertThat(acc.isFinalized()).isFalse();
        assertThat(acc.finalUsageIfPresent()).isEmpty();
        // Partial view exposes the seeded prompt + observed chunks as a provisional (non-accounting) signal.
        assertThat(acc.partialUsage()).isEqualTo(new Usage(7, 1));
        assertThatThrownBy(acc::finalUsage)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unconfirmed");
    }

    @Test
    void partialUsage_convergesToAuthoritativeAfterFinalization() {
        List<StreamEvent> events = projector.project(response("a b c", new Usage(4, 12)), ID, CREATED);

        StreamingUsageAccumulator acc = new StreamingUsageAccumulator(4);
        // Mid-stream partial is provisional and not the authoritative completion total.
        acc.accept(events.get(0)); // role
        acc.accept(events.get(1)); // first content
        assertThat(acc.isFinalized()).isFalse();
        assertThat(acc.partialUsage()).isEqualTo(new Usage(4, 1));

        acc.acceptAll(events);
        // After full fold, partial == final authoritative.
        assertThat(acc.partialUsage()).isEqualTo(acc.finalUsage());
        assertThat(acc.finalUsage()).isEqualTo(new Usage(4, 12));
    }

    @Test
    void deterministic_sameResponseSameChunksSameFinalUsageAcrossRepeats() {
        CompletionResponse response = response("repeat me twice", new Usage(6, 13));

        Usage first = StreamingUsageAccumulator.fold(projector.project(response, ID, CREATED));
        Usage second = StreamingUsageAccumulator.fold(projector.project(response, ID, CREATED));

        assertThat(first).isEqualTo(second).isEqualTo(new Usage(6, 13));
    }
}
