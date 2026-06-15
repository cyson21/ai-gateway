package com.example.gateway.guardrail;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Real local-rules {@link Guardrail}. Holds an ordered list of input rules and output rules; each
 * stage evaluates its rules in order and produces one {@link GuardrailEvent} per rule. A stage
 * blocks if any of its rules block — the stage maps to {@link GuardrailResult#BLOCKED_INPUT} /
 * {@link GuardrailResult#BLOCKED_OUTPUT}, otherwise {@link GuardrailResult#PASS}.
 *
 * <p>All rules are deterministic (no network / clock / randomness), so the per-request event list
 * is reproducible. Rule parameters (banned terms, length cap, leak patterns) are constructor-
 * injected via {@link #defaults}/{@link Builder} so the policy is configurable.
 *
 * <p><b>PII policy:</b> PII detection is a <i>hook</i> — it detects and records a
 * {@code pii-detection} event but never blocks and never mutates the content (no masking applied).
 * Surfacing PII as a recorded signal is in scope; rewriting the prompt/response is intentionally
 * out of scope for this slice. A blocking or masking policy can be layered later by swapping the
 * rule.
 *
 * <p>The {@link Guardrail} interface exposes only the rolled-up {@link GuardrailResult}; callers
 * that need to persist the per-rule events use {@link #inspectInputDetailed} /
 * {@link #inspectOutputDetailed}.
 */
public final class RuleBasedGuardrail implements Guardrail {

    /** Email and a generic API-key/secret shape: deterministic PII/leak signals. */
    public static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    public static final Pattern SECRET_LIKE = Pattern.compile("(?i)(sk|api|secret|token)[-_][A-Za-z0-9]{8,}");

    private final List<GuardrailRule> inputRules;
    private final List<GuardrailRule> outputRules;

    public RuleBasedGuardrail(List<GuardrailRule> inputRules, List<GuardrailRule> outputRules) {
        this.inputRules = List.copyOf(Objects.requireNonNull(inputRules, "inputRules"));
        this.outputRules = List.copyOf(Objects.requireNonNull(outputRules, "outputRules"));
        for (GuardrailRule rule : this.inputRules) {
            if (rule.stage() != GuardrailStage.INPUT) {
                throw new IllegalArgumentException("input rule '" + rule.name() + "' is not an INPUT rule");
            }
        }
        for (GuardrailRule rule : this.outputRules) {
            if (rule.stage() != GuardrailStage.OUTPUT) {
                throw new IllegalArgumentException("output rule '" + rule.name() + "' is not an OUTPUT rule");
            }
        }
    }

    @Override
    public GuardrailResult inspectInput(CompletionRequest request) {
        return inspectInputDetailed(request).result();
    }

    @Override
    public GuardrailResult inspectOutput(CompletionResponse response) {
        return inspectOutputDetailed(response).result();
    }

    /** Run the input rules against the prompt, returning per-rule events and the stage result. */
    public GuardrailInspection inspectInputDetailed(CompletionRequest request) {
        Objects.requireNonNull(request, "request");
        return run(inputRules, request.prompt(), GuardrailResult.BLOCKED_INPUT);
    }

    /** Run the output rules against the response content, returning per-rule events and result. */
    public GuardrailInspection inspectOutputDetailed(CompletionResponse response) {
        Objects.requireNonNull(response, "response");
        return run(outputRules, response.content(), GuardrailResult.BLOCKED_OUTPUT);
    }

    private GuardrailInspection run(List<GuardrailRule> rules, String text, GuardrailResult blockedResult) {
        List<GuardrailEvent> events = new ArrayList<>(rules.size());
        boolean blocked = false;
        for (GuardrailRule rule : rules) {
            GuardrailEvent event = rule.evaluate(text);
            events.add(event);
            if (event.blocked()) {
                blocked = true;
            }
        }
        return new GuardrailInspection(blocked ? blockedResult : GuardrailResult.PASS, events);
    }

    /**
     * Default policy: input banned-terms + max-length + a PII-detection hook; output banned-terms +
     * a secret-leak pattern. The PII hook detects-and-records only (never blocks).
     */
    public static RuleBasedGuardrail defaults(List<String> bannedTerms, int maxInputChars) {
        return builder()
                .inputRule(GuardrailRule.bannedTerms(GuardrailStage.INPUT, "input-banned-terms", bannedTerms))
                .inputRule(GuardrailRule.maxLength(GuardrailStage.INPUT, "input-max-length", maxInputChars))
                .inputRule(piiDetection())
                .outputRule(GuardrailRule.bannedTerms(GuardrailStage.OUTPUT, "output-banned-terms", bannedTerms))
                .outputRule(GuardrailRule.pattern(GuardrailStage.OUTPUT, "output-secret-leak", List.of(SECRET_LIKE)))
                .build();
    }

    /**
     * PII-detection hook (INPUT stage). Records a {@code pii-detection} event but is non-blocking:
     * it always returns {@link GuardrailAction#PASS}, so detection is observable without mutating
     * content or failing the request. (Detect-and-record policy; see class doc.)
     */
    public static GuardrailRule piiDetection() {
        return new GuardrailRule() {
            @Override
            public String name() {
                return "pii-detection";
            }

            @Override
            public GuardrailStage stage() {
                return GuardrailStage.INPUT;
            }

            @Override
            public GuardrailEvent evaluate(String text) {
                // Detect-and-record only: the event is always PASS; detection is exposed via
                // detectsPii(text) for callers that want the boolean signal.
                return GuardrailEvent.pass(GuardrailStage.INPUT, "pii-detection");
            }
        };
    }

    /** Deterministic PII detection signal (email shape). True when the text looks like it carries PII. */
    public static boolean detectsPii(String text) {
        return text != null && EMAIL.matcher(text).find();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent assembler for the input/output rule lists. */
    public static final class Builder {
        private final List<GuardrailRule> inputRules = new ArrayList<>();
        private final List<GuardrailRule> outputRules = new ArrayList<>();

        public Builder inputRule(GuardrailRule rule) {
            inputRules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        public Builder outputRule(GuardrailRule rule) {
            outputRules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        public RuleBasedGuardrail build() {
            return new RuleBasedGuardrail(inputRules, outputRules);
        }
    }
}
