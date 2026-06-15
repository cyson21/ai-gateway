package com.example.gateway.resilience;

import java.util.List;

/**
 * Sink for fallback-chain attempts. A {@link FallbackChain} run produces an ordered
 * {@link FallbackEvent} list; this port records those events against a request so each attempt
 * lands as one {@code fallback_events} row.
 *
 * <p>Same store-port shape as the D1 {@code RequestLogStore} slice: an in-memory fake proves the
 * append/projection/ordering contract here, while the real R2DBC/PostgreSQL {@code fallback_events}
 * INSERT and the {@code request_id} FK to {@code request_logs} are a later persistence slice. The
 * {@code requestId} is carried as an opaque correlation key so the fake can group events without
 * modeling the FK.
 */
public interface FallbackEventStore {

    /** Record one event for a request, returning the stored event. */
    FallbackEvent append(long requestId, FallbackEvent event);

    /** Record an ordered batch of events for a request (e.g. a whole chain run), in order. */
    List<FallbackEvent> appendAll(long requestId, List<FallbackEvent> events);

    /** All events for a request, in append (attempt) order. Test/assertion read side. */
    List<FallbackEvent> forRequest(long requestId);

    /** All events across requests, in append order. */
    List<FallbackEvent> all();
}
