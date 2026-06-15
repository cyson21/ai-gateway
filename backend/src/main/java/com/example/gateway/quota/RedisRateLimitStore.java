package com.example.gateway.quota;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed {@link RateLimitStore}. Each {@code key} maps to a sorted set {@code rl:{key}}
 * whose members are hit timestamps (scored by the same millisecond value). A single Lua
 * {@code EVAL} performs the whole evict-count-record sequence server-side, so it is one
 * indivisible operation even across concurrent clients and instances — the contract the in-memory
 * {@link InMemoryRateLimitStore} fakes for unit tests.
 *
 * <p>The script:
 * <ol>
 *   <li>{@code ZREMRANGEBYSCORE} drops members at or below {@code nowMillis - windowMillis}
 *       (the half-open window {@code (cutoff, nowMillis]});</li>
 *   <li>{@code ZCARD} reads the surviving hit count;</li>
 *   <li>if the count is below {@code maxHits}, {@code ZADD} records the new hit and returns 1;
 *       otherwise it records nothing and returns 0.</li>
 * </ol>
 * A TTL of one window is refreshed on every admitted hit so idle keys expire on their own. Because
 * timestamps can collide within a millisecond, members are made unique by suffixing a monotonic
 * sequence drawn from a per-key {@code rl:seq:{key}} counter, while the score stays {@code nowMillis}
 * so eviction remains time-based.
 */
public final class RedisRateLimitStore implements RateLimitStore {

    /**
     * KEYS[1] = sorted-set key, KEYS[2] = sequence key.
     * ARGV[1] = nowMillis, ARGV[2] = windowMillis, ARGV[3] = maxHits.
     */
    private static final String SCRIPT =
            "local cutoff = tonumber(ARGV[1]) - tonumber(ARGV[2]) "
            + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff) "
            + "local count = redis.call('ZCARD', KEYS[1]) "
            + "if count < tonumber(ARGV[3]) then "
            + "  local seq = redis.call('INCR', KEYS[2]) "
            + "  redis.call('ZADD', KEYS[1], tonumber(ARGV[1]), tostring(ARGV[1]) .. '-' .. seq) "
            + "  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2])) "
            + "  redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[2])) "
            + "  return 1 "
            + "end "
            + "return 0";

    private final RedisCommands<String, String> commands;

    public RedisRateLimitStore(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    @Override
    public boolean tryAcquire(String key, long nowMillis, long windowMillis, long maxHits) {
        Long admitted = commands.eval(
                SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {"rl:" + key, "rl:seq:" + key},
                Long.toString(nowMillis),
                Long.toString(windowMillis),
                Long.toString(maxHits));
        return admitted != null && admitted == 1L;
    }
}
