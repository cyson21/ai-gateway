package com.example.gateway.quota;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed {@link BudgetStore}. Each {@code key} maps to a hash {@code bg:{key}} with two
 * fields, {@code tokens} and {@code cost}, holding the consumed totals for the period. Every
 * mutation is a single Lua {@code EVAL} so the read-check-write sequence is indivisible
 * server-side, even across concurrent clients and instances — the contract the in-memory
 * {@link InMemoryBudgetStore} fakes for unit tests.
 *
 * <p>{@link #reserve} reads both consumed totals, admits the draw only if <em>both</em> the token
 * and cost limits would still hold, and records both with {@code HINCRBY} only then; otherwise it
 * records nothing (the HINCRBY-with-rollback contract, realised here as a single check-then-write
 * so no rollback is ever needed). {@link #release} and {@link #commit} adjust both totals and clamp
 * each at zero in the same script, so the {@code consumed >= 0} invariant (mirroring the
 * {@code budgets_consumed_nonneg} CHECK) holds under any interleaving.
 */
public final class RedisBudgetStore implements BudgetStore {

    /**
     * KEYS[1] = budget hash. ARGV[1] = tokens, ARGV[2] = cost, ARGV[3] = tokenLimit,
     * ARGV[4] = costLimit. Returns 1 if both draws fit and were recorded, else 0.
     */
    private static final String RESERVE_SCRIPT =
            "local t = tonumber(redis.call('HGET', KEYS[1], 'tokens') or '0') "
            + "local c = tonumber(redis.call('HGET', KEYS[1], 'cost') or '0') "
            + "if t + tonumber(ARGV[1]) > tonumber(ARGV[3]) then return 0 end "
            + "if c + tonumber(ARGV[2]) > tonumber(ARGV[4]) then return 0 end "
            + "redis.call('HINCRBY', KEYS[1], 'tokens', tonumber(ARGV[1])) "
            + "redis.call('HINCRBY', KEYS[1], 'cost', tonumber(ARGV[2])) "
            + "return 1";

    /**
     * KEYS[1] = budget hash. ARGV[1] = token delta, ARGV[2] = cost delta. Subtracts each delta and
     * clamps the result at zero. Used by both release and commit (commit passes a net delta).
     */
    private static final String CLAMP_SUBTRACT_SCRIPT =
            "local t = tonumber(redis.call('HGET', KEYS[1], 'tokens') or '0') - tonumber(ARGV[1]) "
            + "local c = tonumber(redis.call('HGET', KEYS[1], 'cost') or '0') - tonumber(ARGV[2]) "
            + "if t < 0 then t = 0 end "
            + "if c < 0 then c = 0 end "
            + "redis.call('HSET', KEYS[1], 'tokens', t, 'cost', c) "
            + "return 1";

    private final RedisCommands<String, String> commands;

    public RedisBudgetStore(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    @Override
    public boolean reserve(String key, long tokens, long cost, long tokenLimit, long costLimit) {
        Long admitted = commands.eval(
                RESERVE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {"bg:" + key},
                Long.toString(tokens),
                Long.toString(cost),
                Long.toString(tokenLimit),
                Long.toString(costLimit));
        return admitted != null && admitted == 1L;
    }

    @Override
    public void release(String key, long tokens, long cost) {
        commands.eval(
                CLAMP_SUBTRACT_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {"bg:" + key},
                Long.toString(tokens),
                Long.toString(cost));
    }

    @Override
    public void commit(String key, long reservedTokens, long reservedCost, long actualTokens, long actualCost) {
        // Net effect: consumed -= (reserved - actual), then clamp at zero. A single subtract of the
        // net delta keeps it to one server-side step while preserving the non-negative invariant.
        commands.eval(
                CLAMP_SUBTRACT_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {"bg:" + key},
                Long.toString(reservedTokens - actualTokens),
                Long.toString(reservedCost - actualCost));
    }

    /** Currently consumed tokens for {@code key} (0 if never touched). For test assertions. */
    public long consumedTokens(String key) {
        String value = commands.hget("bg:" + key, "tokens");
        return value == null ? 0L : Long.parseLong(value);
    }

    /** Currently consumed cost for {@code key} (0 if never touched). For test assertions. */
    public long consumedCost(String key) {
        String value = commands.hget("bg:" + key, "cost");
        return value == null ? 0L : Long.parseLong(value);
    }
}
