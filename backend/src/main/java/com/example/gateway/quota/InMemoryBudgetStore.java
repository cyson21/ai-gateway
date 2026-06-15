package com.example.gateway.quota;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link BudgetStore} keeping a per-key pair of consumed totals (tokens and cost). Each
 * {@code key} maps to a {@link Consumed} cell; every mutation synchronizes on that cell, so the
 * read-check-record sequence in {@link #reserve} is indivisible per key while different keys
 * (tenants/periods) stay independent.
 *
 * <p>{@link #release} and {@link #commit} clamp each total at zero, so the {@code consumed >= 0}
 * invariant holds under any interleaving of concurrent reserve/release/commit calls — the
 * single-instance stand-in for the Redis-backed store. Cross-instance atomicity is the E1 slice.
 */
public final class InMemoryBudgetStore implements BudgetStore {

    private static final class Consumed {
        long tokens;
        long cost;
    }

    private final ConcurrentHashMap<String, Consumed> cells = new ConcurrentHashMap<>();

    @Override
    public boolean reserve(String key, long tokens, long cost, long tokenLimit, long costLimit) {
        Consumed cell = cells.computeIfAbsent(key, k -> new Consumed());
        synchronized (cell) {
            if (cell.tokens + tokens > tokenLimit) {
                return false;
            }
            if (cell.cost + cost > costLimit) {
                return false;
            }
            cell.tokens += tokens;
            cell.cost += cost;
            return true;
        }
    }

    @Override
    public void release(String key, long tokens, long cost) {
        Consumed cell = cells.computeIfAbsent(key, k -> new Consumed());
        synchronized (cell) {
            cell.tokens = Math.max(0, cell.tokens - tokens);
            cell.cost = Math.max(0, cell.cost - cost);
        }
    }

    @Override
    public void commit(String key, long reservedTokens, long reservedCost, long actualTokens, long actualCost) {
        Consumed cell = cells.computeIfAbsent(key, k -> new Consumed());
        synchronized (cell) {
            cell.tokens = Math.max(0, cell.tokens - reservedTokens + actualTokens);
            cell.cost = Math.max(0, cell.cost - reservedCost + actualCost);
        }
    }

    /** Currently reserved tokens for {@code key} (0 if never touched). For test assertions. */
    public long consumedTokens(String key) {
        Consumed cell = cells.get(key);
        if (cell == null) {
            return 0;
        }
        synchronized (cell) {
            return cell.tokens;
        }
    }

    /** Currently reserved cost for {@code key} (0 if never touched). For test assertions. */
    public long consumedCost(String key) {
        Consumed cell = cells.get(key);
        if (cell == null) {
            return 0;
        }
        synchronized (cell) {
            return cell.cost;
        }
    }
}
