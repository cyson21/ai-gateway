package com.example.gateway.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.Usage;
import com.example.gateway.streaming.ChatCompletionChunk.Delta;
import com.example.gateway.streaming.StreamEvent.ChunkEvent;
import com.example.gateway.streaming.StreamEvent.Done;

import org.junit.jupiter.api.Test;

/**
 * Streaming-1: pure projection of a completed {@link CompletionResponse} into the
 * OpenAI-compatible {@code chat.completion.chunk} sequence. No clock, no randomness, no I/O;
 * metadata ({@code id}/{@code created}) is injected so output is deterministic. The keystone
 * is the recombination invariant: content deltas re-joined must equal the original content.
 */
class StreamChunkProjectorTest {

    private static final String ID = "chatcmpl-fixed-001";
    private static final long CREATED = 1_700_000_000L;

    private final StreamChunkProjector projector = new StreamChunkProjector();

    private CompletionResponse response(String content) {
        return new CompletionResponse(content, "gpt-4o", new Usage(3, 5));
    }

    private List<ChatCompletionChunk> chunks(List<StreamEvent> events) {
        return events.stream()
                .filter(e -> e instanceof ChunkEvent)
                .map(e -> ((ChunkEvent) e).chunk())
                .toList();
    }

    private String recombine(List<StreamEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (ChatCompletionChunk chunk : chunks(events)) {
            if (chunk.delta().content() != null) {
                sb.append(chunk.delta().content());
            }
        }
        return sb.toString();
    }

    @Test
    void multiWordContent_emitsRoleFirstThenContentDeltasThenFinishThenDone() {
        List<StreamEvent> events = projector.project(response("Hello there general"), ID, CREATED);
        List<ChatCompletionChunk> chunks = chunks(events);

        // role chunk + 3 content chunks + terminal chunk = 5 chunks, then [DONE].
        assertThat(chunks).hasSize(5);

        // 1) role-opening chunk.
        assertThat(chunks.get(0).delta()).isEqualTo(Delta.role("assistant"));
        assertThat(chunks.get(0).finishReason()).isNull();

        // 2) content deltas only (no role).
        assertThat(chunks.get(1).delta()).isEqualTo(Delta.content("Hello"));
        assertThat(chunks.get(2).delta()).isEqualTo(Delta.content(" there"));
        assertThat(chunks.get(3).delta()).isEqualTo(Delta.content(" general"));
        assertThat(chunks.subList(1, 4)).allSatisfy(c -> {
            assertThat(c.delta().role()).isNull();
            assertThat(c.finishReason()).isNull();
        });

        // 3) terminal chunk: empty delta + finish_reason=stop.
        ChatCompletionChunk terminal = chunks.get(4);
        assertThat(terminal.delta()).isEqualTo(Delta.empty());
        assertThat(terminal.finishReason()).isEqualTo("stop");

        // 4) [DONE] sentinel is the last event.
        assertThat(events.get(events.size() - 1)).isEqualTo(Done.INSTANCE);
        assertThat(Done.SENTINEL).isEqualTo("[DONE]");
    }

    @Test
    void recombinationInvariant_contentDeltasRejoinToOriginal() {
        String content = "  Leading spaces and  doubled  interior gaps trailing ";
        List<StreamEvent> events = projector.project(response(content), ID, CREATED);

        assertThat(recombine(events)).isEqualTo(content);
    }

    @Test
    void emptyContent_noContentChunksButRoleFinishAndDoneRemain() {
        List<StreamEvent> events = projector.project(response(""), ID, CREATED);
        List<ChatCompletionChunk> chunks = chunks(events);

        // role chunk + terminal chunk only.
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).delta()).isEqualTo(Delta.role("assistant"));
        assertThat(chunks.get(1).delta()).isEqualTo(Delta.empty());
        assertThat(chunks.get(1).finishReason()).isEqualTo("stop");
        assertThat(events.get(events.size() - 1)).isEqualTo(Done.INSTANCE);

        // No content delta carries text.
        assertThat(chunks).noneSatisfy(c -> assertThat(c.delta().content()).isNotNull());
    }

    @Test
    void singleTokenContent_roleThenOneContentThenFinishConsistent() {
        List<StreamEvent> events = projector.project(response("Hi"), ID, CREATED);
        List<ChatCompletionChunk> chunks = chunks(events);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).delta()).isEqualTo(Delta.role("assistant"));
        assertThat(chunks.get(1).delta()).isEqualTo(Delta.content("Hi"));
        assertThat(chunks.get(1).finishReason()).isNull();
        assertThat(chunks.get(2).finishReason()).isEqualTo("stop");
        assertThat(recombine(events)).isEqualTo("Hi");
    }

    @Test
    void allChunksShareSameIdModelCreatedMetadata() {
        List<StreamEvent> events = projector.project(response("alpha beta gamma"), ID, CREATED);

        assertThat(chunks(events)).allSatisfy(c -> {
            assertThat(c.id()).isEqualTo(ID);
            assertThat(c.model()).isEqualTo("gpt-4o");
            assertThat(c.created()).isEqualTo(CREATED);
            assertThat(c.object()).isEqualTo("chat.completion.chunk");
        });
    }

    @Test
    void deterministic_sameInputAndInjectedMetadataYieldEqualOutput() {
        CompletionResponse response = response("one two three");

        List<StreamEvent> first = projector.project(response, ID, CREATED);
        List<StreamEvent> second = projector.project(response, ID, CREATED);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void tokenize_emptyAndWhitespaceOnly() {
        assertThat(StreamChunkProjector.tokenize("")).isEmpty();
        assertThat(StreamChunkProjector.tokenize(null)).isEmpty();
        // Whitespace-only content recombines exactly via a single token.
        assertThat(String.join("", StreamChunkProjector.tokenize("   "))).isEqualTo("   ");
    }
}
