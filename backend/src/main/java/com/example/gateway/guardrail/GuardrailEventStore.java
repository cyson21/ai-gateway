package com.example.gateway.guardrail;

import java.util.List;

/**
 * Sink for guardrail-rule evaluations. A {@link RuleBasedGuardrail} stage produces an ordered
 * {@link GuardrailEvent} list (one event per rule); this port records those events against a request
 * so each evaluation lands as one {@code guardrail_events} row.
 *
 * <p>Same store-port shape as the D1 {@code RequestLogStore} and M4-1 {@code FallbackEventStore}
 * slices: an in-memory fake proves the append / projection / ordering contract here, while the real
 * R2DBC/PostgreSQL {@code guardrail_events} INSERT and the {@code request_id} FK to
 * {@code request_logs} are a later persistence slice. The {@code requestId} is carried as an opaque
 * correlation key so the fake can group events without modeling the FK.
 */
public interface GuardrailEventStore {

    /** Record one event for a request, returning the stored event. */
    GuardrailEvent append(long requestId, GuardrailEvent event);

    /** Record an ordered batch of events for a request (e.g. a whole stage run), in order. */
    List<GuardrailEvent> appendAll(long requestId, List<GuardrailEvent> events);

    /** All events for a request, in append (rule) order. Test/assertion read side. */
    List<GuardrailEvent> forRequest(long requestId);

    /** All events across requests, in append order. */
    List<GuardrailEvent> all();
}
