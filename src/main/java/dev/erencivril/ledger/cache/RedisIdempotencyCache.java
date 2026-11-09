package dev.erencivril.ledger.cache;

import dev.erencivril.ledger.application.Ports.CachedKey;
import dev.erencivril.ledger.application.Ports.IdempotencyCache;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

/**
 * A read-through shortcut for idempotent replays. Nothing here is trusted for correctness: a miss, a
 * stale entry or a Redis outage only costs one Postgres lookup, and errors are swallowed on purpose.
 */
public final class RedisIdempotencyCache implements IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyCache.class);
    private static final long TTL_SECONDS = 24 * 60 * 60;

    private final JedisPooled redis;

    public RedisIdempotencyCache(JedisPooled redis) {
        this.redis = redis;
    }

    @Override
    public Optional<CachedKey> get(String key) {
        try {
            String value = redis.get(redisKey(key));
            if (value == null) {
                return Optional.empty();
            }
            int separator = value.indexOf(':');
            if (separator < 0) {
                return Optional.empty();
            }
            return Optional.of(new CachedKey(value.substring(0, separator), UUID.fromString(value.substring(separator + 1))));
        } catch (RuntimeException e) {
            log.warn("Idempotency cache read failed; using Postgres: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String fingerprint, UUID transferId) {
        try {
            redis.set(redisKey(key), fingerprint + ":" + transferId, SetParams.setParams().ex(TTL_SECONDS));
        } catch (RuntimeException e) {
            log.warn("Idempotency cache write failed; ignoring: {}", e.toString());
        }
    }

    private static String redisKey(String key) {
        return "idem:" + key;
    }
}
