package com.example.gateway.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.Usage;

/**
 * M4-4: the real local-rules {@link RuleBasedGuardrail}. Proves PASS/BLOCK per rule, the stage
 * roll-up to {@link GuardrailResult}, length boundaries, output leak detection, and the
 * detect-and-record PII hook policy. Deterministic — no network/clock/randomness.
 */
class RuleBasedGuardrailTest {

    private static final int MAX_CHARS = 10;
    private static final List<String> BANNED = List.of("forbidden", "bomb");

    private RuleBasedGuardrail guardrail() {
        return RuleBasedGuardrail.defaults(BANNED, MAX_CHARS);
    }

    private CompletionRequest input(String prompt) {
        return new CompletionRequest("t", "gpt-4o", prompt, 16, false);
    }

    private CompletionResponse output(String content) {
        return new CompletionResponse(content, "m-a", Usage.zero());
    }

    @Test
    void cleanInputPassesWithPerRulePassEvents() {
        GuardrailInspection in = guardrail().inspectInputDetailed(input("hello"));

        assertThat(in.result()).isEqualTo(GuardrailResult.PASS);
        assertThat(in.events()).extracting(GuardrailEvent::action)
                .containsOnly(GuardrailAction.PASS);
        assertThat(in.events()).extracting(GuardrailEvent::rule)
                .containsExactly("input-banned-terms", "input-max-length", "pii-detection");
        assertThat(in.events()).extracting(GuardrailEvent::stage)
                .containsOnly(GuardrailStage.INPUT);
    }

    @Test
    void bannedTermInInputBlocks() {
        GuardrailInspection in = guardrail().inspectInputDetailed(input("a BOMB")); // within length cap

        assertThat(in.result()).isEqualTo(GuardrailResult.BLOCKED_INPUT);
        GuardrailEvent banned = in.events().get(0);
        assertThat(banned.rule()).isEqualTo("input-banned-terms");
        assertThat(banned.action()).isEqualTo(GuardrailAction.BLOCK);
        // Other rules still produce events; only one BLOCK is needed to block the stage.
        assertThat(in.events()).hasSize(3);
        assertThat(in.events().stream().filter(GuardrailEvent::blocked).count()).isEqualTo(1);
    }

    @Test
    void lengthBoundaryExactlyAtCapPasses() {
        String exact = "a".repeat(MAX_CHARS); // length == cap
        GuardrailInspection in = guardrail().inspectInputDetailed(input(exact));

        assertThat(in.result()).isEqualTo(GuardrailResult.PASS);
        assertThat(eventFor(in, "input-max-length").action()).isEqualTo(GuardrailAction.PASS);
    }

    @Test
    void lengthBoundaryOnePastCapBlocks() {
        String over = "a".repeat(MAX_CHARS + 1); // cap + 1
        GuardrailInspection in = guardrail().inspectInputDetailed(input(over));

        assertThat(in.result()).isEqualTo(GuardrailResult.BLOCKED_INPUT);
        assertThat(eventFor(in, "input-max-length").action()).isEqualTo(GuardrailAction.BLOCK);
    }

    @Test
    void cleanOutputPasses() {
        GuardrailInspection out = guardrail().inspectOutputDetailed(output("all good"));

        assertThat(out.result()).isEqualTo(GuardrailResult.PASS);
        assertThat(out.events()).extracting(GuardrailEvent::stage).containsOnly(GuardrailStage.OUTPUT);
    }

    @Test
    void bannedTermInOutputBlocks() {
        GuardrailInspection out = guardrail().inspectOutputDetailed(output("here is the FORBIDDEN answer"));

        assertThat(out.result()).isEqualTo(GuardrailResult.BLOCKED_OUTPUT);
        assertThat(eventFor(out, "output-banned-terms").action()).isEqualTo(GuardrailAction.BLOCK);
    }

    @Test
    void secretLeakPatternInOutputBlocks() {
        GuardrailInspection out = guardrail().inspectOutputDetailed(output("your key is sk-ABCDEFGH1234"));

        assertThat(out.result()).isEqualTo(GuardrailResult.BLOCKED_OUTPUT);
        assertThat(eventFor(out, "output-secret-leak").action()).isEqualTo(GuardrailAction.BLOCK);
    }

    @Test
    void piiHookDetectsButRecordsAsPassAndDoesNotBlock() {
        // Detect-and-record policy: PII is detectable but does NOT block and content is not mutated.
        String withPii = "ab@c.io"; // email shape, within length cap, no banned terms
        GuardrailInspection in = guardrail().inspectInputDetailed(input(withPii));

        assertThat(in.result()).isEqualTo(GuardrailResult.PASS);
        assertThat(eventFor(in, "pii-detection").action()).isEqualTo(GuardrailAction.PASS);
        assertThat(RuleBasedGuardrail.detectsPii(withPii)).isTrue();
        assertThat(RuleBasedGuardrail.detectsPii("no pii here")).isFalse();
    }

    @Test
    void interfaceMethodsReturnRolledUpResult() {
        Guardrail g = guardrail();
        assertThat(g.inspectInput(input("hello"))).isEqualTo(GuardrailResult.PASS);
        assertThat(g.inspectInput(input("the bomb"))).isEqualTo(GuardrailResult.BLOCKED_INPUT);
        assertThat(g.inspectOutput(output("ok"))).isEqualTo(GuardrailResult.PASS);
        assertThat(g.inspectOutput(output("forbidden"))).isEqualTo(GuardrailResult.BLOCKED_OUTPUT);
    }

    private static GuardrailEvent eventFor(GuardrailInspection inspection, String rule) {
        return inspection.events().stream()
                .filter(e -> e.rule().equals(rule))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no event for rule " + rule));
    }
}
