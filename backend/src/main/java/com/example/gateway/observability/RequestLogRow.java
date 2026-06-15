package com.example.gateway.observability;

/**
 * Projection of a {@link RequestRecord} onto the {@code request_logs} columns (1:1 with the
 * V1 schema). Enum fields are stored as their {@code name()} TEXT; nullable columns
 * ({@code chosenProvider}, {@code chosenModel}, {@code message}) carry {@code null} through
 * unchanged so a quota-rejected or guardrail-blocked row keeps its provider/model null.
 *
 * <p>{@code createdAt} is supplied by an injected clock so the timestamp is deterministic in
 * tests; the real {@code created_at DEFAULT now()} persistence is the E1 slice.
 */
public record RequestLogRow(
        String tenantId,
        String alias,
        String mode,
        String chosenProvider,
        String chosenModel,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        long cost,
        String cacheType,
        int fallbackCount,
        String guardrailResult,
        String budgetOutcome,
        String message,
        long createdAtMillis) {

    /**
     * Project a {@link RequestRecord} onto the {@code request_logs} columns. Enums map to
     * {@code name()}; null provider/model/message are preserved as null.
     *
     * @param record        the pipeline's per-request record (never null)
     * @param createdAtMillis the row timestamp (injected clock), stand-in for {@code created_at}
     */
    public static RequestLogRow from(RequestRecord record, long createdAtMillis) {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        return new RequestLogRow(
                record.tenantId(),
                record.alias(),
                name(record.mode()),
                record.chosenProvider(),
                record.chosenModel(),
                record.promptTokens(),
                record.completionTokens(),
                record.latencyMs(),
                record.cost(),
                name(record.cacheType()),
                record.fallbackCount(),
                name(record.guardrailResult()),
                name(record.budgetOutcome()),
                record.message(),
                createdAtMillis);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
