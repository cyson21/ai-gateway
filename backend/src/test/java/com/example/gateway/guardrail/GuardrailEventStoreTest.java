package com.example.gateway.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.gateway.provider.CompletionRequest;

/**
 * M4-4: the {@link GuardrailEventStore} append/projection/ordering contract, proven against the
 * in-memory fake (same store-port shape as D1 {@code RequestLogStore} and M4-1
 * {@code FallbackEventStore}). Real {@code guardrail_events} INSERT and the {@code request_id} FK
 * are a later persistence slice.
 */
class GuardrailEventStoreTest {

    @Test
    void appendAccumulatesInOrderPerRequest() {
        GuardrailEventStore store = new InMemoryGuardrailEventStore();

        store.append(1L, GuardrailEvent.pass(GuardrailStage.INPUT, "input-banned-terms"));
        store.append(1L, GuardrailEvent.block(GuardrailStage.INPUT, "input-max-length"));
        store.append(2L, GuardrailEvent.pass(GuardrailStage.OUTPUT, "output-banned-terms"));

        List<GuardrailEvent> r1 = store.forRequest(1L);
        assertThat(r1).hasSize(2);
        assertThat(r1.get(0).stage()).isEqualTo(GuardrailStage.INPUT);
        assertThat(r1.get(0).rule()).isEqualTo("input-banned-terms");
        assertThat(r1.get(0).action()).isEqualTo(GuardrailAction.PASS);
        assertThat(r1.get(1).rule()).isEqualTo("input-max-length");
        assertThat(r1.get(1).action()).isEqualTo(GuardrailAction.BLOCK);

        assertThat(store.forRequest(2L)).hasSize(1);
        assertThat(store.forRequest(99L)).isEmpty();
        assertThat(store.all()).hasSize(3);
    }

    @Test
    void appendAllRecordsAStageRunInOrder() {
        GuardrailEventStore store = new InMemoryGuardrailEventStore();
        List<GuardrailEvent> stage = List.of(
                GuardrailEvent.pass(GuardrailStage.INPUT, "input-banned-terms"),
                GuardrailEvent.block(GuardrailStage.INPUT, "input-max-length"),
                GuardrailEvent.pass(GuardrailStage.INPUT, "pii-detection"));

        store.appendAll(7L, stage);

        List<GuardrailEvent> stored = store.forRequest(7L);
        assertThat(stored).containsExactlyElementsOf(stage);
        assertThat(stored).extracting(GuardrailEvent::rule)
                .containsExactly("input-banned-terms", "input-max-length", "pii-detection");
        assertThat(stored).extracting(GuardrailEvent::action)
                .containsExactly(GuardrailAction.PASS, GuardrailAction.BLOCK, GuardrailAction.PASS);
    }

    @Test
    void guardrailInspectionEventsFeedTheStore() {
        // End-to-end: a real guardrail stage's per-rule events land as rows for a request.
        RuleBasedGuardrail guardrail = RuleBasedGuardrail.defaults(List.of("bomb"), 100);
        GuardrailInspection in = guardrail.inspectInputDetailed(
                new CompletionRequest("t", "gpt-4o", "make a bomb", 16, false));

        GuardrailEventStore store = new InMemoryGuardrailEventStore();
        store.appendAll(42L, in.events());

        assertThat(in.result()).isEqualTo(GuardrailResult.BLOCKED_INPUT);
        List<GuardrailEvent> stored = store.forRequest(42L);
        assertThat(stored).hasSize(3);
        assertThat(stored).extracting(GuardrailEvent::stage).containsOnly(GuardrailStage.INPUT);
        assertThat(stored.stream().filter(GuardrailEvent::blocked).count()).isEqualTo(1);
    }
}
