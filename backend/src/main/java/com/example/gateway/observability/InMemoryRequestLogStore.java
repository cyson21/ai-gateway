package com.example.gateway.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * In-memory {@link RequestLogStore} fake. Appends rows to a single append-ordered list and answers
 * tenant-scoped reads by filtering on {@code tenant_id} — the single-instance stand-in for the
 * {@code request_logs_tenant_created_idx (tenant_id, created_at)} index. The default clock is a
 * monotonic counter so rows have a deterministic, strictly increasing {@code created_at} without a
 * wall clock; callers may inject their own {@link LongSupplier}. Real persistence and the live
 * index are the E1 slice.
 */
public final class InMemoryRequestLogStore implements RequestLogStore {

    private final List<RequestLogRow> rows = new CopyOnWriteArrayList<>();
    private final LongSupplier defaultClock;

    /** Default clock: a monotonic 1,2,3,... counter (deterministic, no wall clock). */
    public InMemoryRequestLogStore() {
        this(monotonic());
    }

    public InMemoryRequestLogStore(LongSupplier defaultClock) {
        this.defaultClock = defaultClock == null ? monotonic() : defaultClock;
    }

    private static LongSupplier monotonic() {
        long[] tick = {0L};
        return () -> ++tick[0];
    }

    @Override
    public RequestLogRow append(RequestRecord record) {
        return append(record, defaultClock);
    }

    @Override
    public RequestLogRow append(RequestRecord record, LongSupplier clockMillis) {
        LongSupplier clock = clockMillis == null ? defaultClock : clockMillis;
        RequestLogRow row = RequestLogRow.from(record, clock.getAsLong());
        rows.add(row);
        return row;
    }

    @Override
    public List<RequestLogRow> forTenant(String tenantId) {
        List<RequestLogRow> out = new ArrayList<>();
        for (RequestLogRow row : rows) {
            if (java.util.Objects.equals(row.tenantId(), tenantId)) {
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<RequestLogRow> all() {
        return List.copyOf(rows);
    }
}
