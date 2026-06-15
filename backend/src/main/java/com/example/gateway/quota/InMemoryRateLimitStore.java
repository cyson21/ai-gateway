package com.example.gateway.quota;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link RateLimitStore} keeping a per-key log of hit timestamps. Each {@code key} maps
 * to an ascending deque of timestamps; {@link #tryAcquire} drops timestamps that have aged out of
 * the window, then admits the hit only while the remaining count is below the limit.
 *
 * <p>Atomicity is provided by synchronizing on the per-key deque, so the evict-count-record
 * sequence is indivisible per key while different keys (tenants/windows) stay independent. This is
 * the single-instance stand-in for the Redis-backed store; cross-instance atomicity is the E1 slice.
 */
public final class InMemoryRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, long nowMillis, long windowMillis, long maxHits) {
        ArrayDeque<Long> hits = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            long cutoff = nowMillis - windowMillis;
            while (!hits.isEmpty() && hits.peekFirst() <= cutoff) {
                hits.pollFirst();
            }
            if (hits.size() >= maxHits) {
                return false;
            }
            hits.addLast(nowMillis);
            return true;
        }
    }
}
