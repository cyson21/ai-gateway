package com.example.gateway.quota;

/**
 * Atomic sliding-window counter behind the rate limit. A single {@link #tryAcquire} call must
 * evict hits older than the window, decide whether another hit fits under the limit, and record
 * the new hit only if it does — all as one indivisible operation, so concurrent callers can never
 * both observe the same free slot. This is the contract a Redis sorted-set Lua script
 * (ZREMRANGEBYSCORE + ZCARD + conditional ZADD) satisfies on a single instance; the in-memory
 * {@link InMemoryRateLimitStore} satisfies it for unit tests. Distributed proof is the E1 slice.
 */
public interface RateLimitStore {

    /**
     * Atomically reserves a slot in {@code key}'s sliding window.
     *
     * @param key          per-tenant, per-window counter key
     * @param nowMillis    timestamp of this hit (window is {@code (nowMillis - windowMillis, nowMillis]})
     * @param windowMillis window length in milliseconds
     * @param maxHits      maximum hits allowed within the window
     * @return {@code true} if the hit was recorded (within limit); {@code false} if rejected
     *         (limit reached) without recording the hit
     */
    boolean tryAcquire(String key, long nowMillis, long windowMillis, long maxHits);
}
