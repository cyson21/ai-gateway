package com.example.gateway.resilience;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link FallbackEventStore} fake. Appends events to a single append-ordered list, keyed
 * by an opaque {@code requestId}, and answers request-scoped reads by filtering on that key — the
 * single-instance stand-in for the {@code fallback_events.request_id} FK grouping. Real persistence
 * and the live FK to {@code request_logs} are a later slice.
 */
public final class InMemoryFallbackEventStore implements FallbackEventStore {

    private record Entry(long requestId, FallbackEvent event) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public FallbackEvent append(long requestId, FallbackEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        entries.add(new Entry(requestId, event));
        return event;
    }

    @Override
    public List<FallbackEvent> appendAll(long requestId, List<FallbackEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("events is required");
        }
        for (FallbackEvent event : events) {
            append(requestId, event);
        }
        return List.copyOf(events);
    }

    @Override
    public List<FallbackEvent> forRequest(long requestId) {
        List<FallbackEvent> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.requestId() == requestId) {
                out.add(entry.event());
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<FallbackEvent> all() {
        List<FallbackEvent> out = new ArrayList<>();
        for (Entry entry : entries) {
            out.add(entry.event());
        }
        return List.copyOf(out);
    }
}
