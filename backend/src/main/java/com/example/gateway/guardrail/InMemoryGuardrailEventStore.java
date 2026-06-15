package com.example.gateway.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link GuardrailEventStore} fake. Appends events to a single append-ordered list, keyed
 * by an opaque {@code requestId}, and answers request-scoped reads by filtering on that key — the
 * single-instance stand-in for the {@code guardrail_events.request_id} FK grouping. Real persistence
 * and the live FK to {@code request_logs} are a later slice.
 */
public final class InMemoryGuardrailEventStore implements GuardrailEventStore {

    private record Entry(long requestId, GuardrailEvent event) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public GuardrailEvent append(long requestId, GuardrailEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        entries.add(new Entry(requestId, event));
        return event;
    }

    @Override
    public List<GuardrailEvent> appendAll(long requestId, List<GuardrailEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("events is required");
        }
        for (GuardrailEvent event : events) {
            append(requestId, event);
        }
        return List.copyOf(events);
    }

    @Override
    public List<GuardrailEvent> forRequest(long requestId) {
        List<GuardrailEvent> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.requestId() == requestId) {
                out.add(entry.event());
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<GuardrailEvent> all() {
        List<GuardrailEvent> out = new ArrayList<>();
        for (Entry entry : entries) {
            out.add(entry.event());
        }
        return List.copyOf(out);
    }
}
