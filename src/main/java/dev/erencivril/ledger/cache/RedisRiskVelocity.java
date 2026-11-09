package dev.erencivril.ledger.cache;

import dev.erencivril.ledger.application.Ports.RiskVelocity;
import dev.erencivril.ledger.domain.risk.VelocitySnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import redis.clients.jedis.JedisPooled;

/**
 * One sorted set per sender, scores are Redis server timestamps. Members contain a unique attempt id
 * and integer USD cents. Prune, add and both window reads happen in one Lua evaluation.
 * Windows are (now - duration, now]. An outage returns unavailable; the pure engine then holds.
 */
public final class RedisRiskVelocity implements RiskVelocity {
    private final JedisPooled pool;
    private static final String SCRIPT = """
        local time = redis.call('TIME')
        local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 86400000)
        redis.call('ZADD', KEYS[1], now, ARGV[1] .. '|' .. ARGV[2])
        redis.call('PEXPIRE', KEYS[1], 86401000)
        local rows = redis.call('ZRANGE', KEYS[1], 0, -1, 'WITHSCORES')
        local c1, a1, c24, a24 = 0, 0, 0, 0
        for i = 1, #rows, 2 do
            local cents = tonumber(string.match(rows[i], '|(%d+)$'))
            c24 = c24 + 1
            a24 = a24 + cents
            if tonumber(rows[i+1]) > now - 3600000 then
                c1 = c1 + 1
                a1 = a1 + cents
            end
        end
        return {c1, a1, c24, a24}
        """;
    public RedisRiskVelocity(JedisPooled pool) { this.pool = pool; }
    @Override
    public VelocitySnapshot record(UUID senderId, BigDecimal amountUsd) {
        try {
            String cents = amountUsd.movePointRight(2).toBigIntegerExact().toString();
            List<?> result = (List<?>) pool.eval(SCRIPT, List.of("risk:velocity:" + senderId),
                    List.of(UUID.randomUUID().toString(), cents));
            return new VelocitySnapshot(true, ((Number) result.get(0)).intValue(),
                    BigDecimal.valueOf(((Number) result.get(1)).longValue(), 2),
                    ((Number) result.get(2)).intValue(), BigDecimal.valueOf(((Number) result.get(3)).longValue(), 2));
        } catch (RuntimeException unavailable) {
            return VelocitySnapshot.unavailable();
        }
    }
}
