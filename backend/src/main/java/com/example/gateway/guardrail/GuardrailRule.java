package com.example.gateway.guardrail;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A deterministic local guardrail rule: it inspects a single piece of text (the input prompt or
 * the output content) for its stage and produces exactly one {@link GuardrailEvent}. Rules carry
 * no network, clock, or randomness, so a given {@code (rule, text)} pair always yields the same
 * verdict.
 *
 * <p>The stage is fixed per rule instance (a rule is built for INPUT or OUTPUT), so the same
 * banned-terms or leak-pattern logic can be reused on either side via the factories below.
 */
public interface GuardrailRule {

    /** Deterministic identifier projected onto {@code guardrail_events.rule}. */
    String name();

    /** Stage this rule runs in; projected onto {@code guardrail_events.stage}. */
    GuardrailStage stage();

    /** Evaluate the text, returning a PASS/BLOCK event for this rule. */
    GuardrailEvent evaluate(String text);

    /**
     * Blocks when the text contains any banned term (case-insensitive substring match). Terms are
     * matched deterministically; an empty term list never blocks.
     */
    static GuardrailRule bannedTerms(GuardrailStage stage, String name, List<String> bannedTerms) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(name, "name");
        List<String> terms = List.copyOf(Objects.requireNonNull(bannedTerms, "bannedTerms")).stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.toLowerCase(Locale.ROOT))
                .toList();
        return new GuardrailRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GuardrailStage stage() {
                return stage;
            }

            @Override
            public GuardrailEvent evaluate(String text) {
                String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);
                for (String term : terms) {
                    if (haystack.contains(term)) {
                        return GuardrailEvent.block(stage, name);
                    }
                }
                return GuardrailEvent.pass(stage, name);
            }
        };
    }

    /**
     * Blocks when the text length exceeds {@code maxChars}. The boundary is inclusive: a text of
     * exactly {@code maxChars} passes; {@code maxChars + 1} blocks.
     */
    static GuardrailRule maxLength(GuardrailStage stage, String name, int maxChars) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(name, "name");
        if (maxChars < 0) {
            throw new IllegalArgumentException("maxChars must be non-negative");
        }
        return new GuardrailRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GuardrailStage stage() {
                return stage;
            }

            @Override
            public GuardrailEvent evaluate(String text) {
                int len = text == null ? 0 : text.length();
                return len > maxChars ? GuardrailEvent.block(stage, name) : GuardrailEvent.pass(stage, name);
            }
        };
    }

    /**
     * Blocks when the text matches any of the given regex patterns — used for output leak patterns
     * (e.g. API-key or secret shapes). Patterns are applied deterministically in order.
     */
    static GuardrailRule pattern(GuardrailStage stage, String name, List<Pattern> patterns) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(name, "name");
        List<Pattern> compiled = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
        return new GuardrailRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GuardrailStage stage() {
                return stage;
            }

            @Override
            public GuardrailEvent evaluate(String text) {
                String haystack = text == null ? "" : text;
                for (Pattern p : compiled) {
                    if (p.matcher(haystack).find()) {
                        return GuardrailEvent.block(stage, name);
                    }
                }
                return GuardrailEvent.pass(stage, name);
            }
        };
    }
}
