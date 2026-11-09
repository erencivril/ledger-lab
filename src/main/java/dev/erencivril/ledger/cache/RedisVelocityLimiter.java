package dev.erencivril.ledger.cache;

import dev.erencivril.ledger.application.ApplicationException.VelocityLimitExceeded;
import dev.erencivril.ledger.application.Ports.VelocityLimiter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * Sliding-window velocity limit per source account: at most {@code maxTransfers} in the last
 * {@code windowSeconds}. The window lives in a Redis sorted set and is updated by one Lua script, so
 * the check and the update are atomic across several app instances. The script uses the Redis clock,
 * so app servers with skewed clocks agree.
 *
 * <p>If Redis is unreachable the limiter fails open by default: transfers go through unlimited and a
 * warning is logged. The ledger is correct without this rule, so it is a risk control, not an
 * integrity control. Set {@code failOpen=false} to reject instead.
 */
public final class RedisVelocityLimiter implements VelocityLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisVelocityLimiter.class);

    private static final String SCRIPT =
            """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local window = tonumber(ARGV[1]) * 1000
            local limit = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= limit then
              local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
              local retry = math.ceil((tonumber(oldest[2]) + window - now) / 1000)
              if retry < 1 then retry = 1 end
              return retry
            end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return 0
            """;

    private final JedisPooled redis;
    private final int maxTransfers;
    private final int windowSeconds;
    private final boolean failOpen;

    public RedisVelocityLimiter(JedisPooled redis, int maxTransfers, int windowSeconds, boolean failOpen) {
        if (maxTransfers < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("maxTransfers and windowSeconds must be at least 1");
        }
        this.redis = redis;
        this.maxTransfers = maxTransfers;
        this.windowSeconds = windowSeconds;
        this.failOpen = failOpen;
    }

    @Override
    public void acquire(UUID sourceAccountId) {
        long retryAfter;
        try {
            Object result = redis.eval(
                    SCRIPT,
                    List.of("velocity:" + sourceAccountId),
                    List.of(
                            String.valueOf(windowSeconds),
                            String.valueOf(maxTransfers),
                            UUID.randomUUID().toString()));
            retryAfter = (Long) result;
        } catch (RuntimeException e) {
            if (failOpen) {
                log.warn("Velocity limiter could not reach Redis; allowing the transfer: {}", e.toString());
                return;
            }
            throw e;
        }
        if (retryAfter > 0) {
            throw new VelocityLimitExceeded(sourceAccountId, maxTransfers, windowSeconds, retryAfter);
        }
    }
}
