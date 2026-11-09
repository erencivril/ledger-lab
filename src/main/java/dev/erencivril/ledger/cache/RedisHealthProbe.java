package dev.erencivril.ledger.cache;

import dev.erencivril.ledger.application.Ports.HealthProbe;
import redis.clients.jedis.JedisPooled;

public final class RedisHealthProbe implements HealthProbe {

    private final JedisPooled redis;

    public RedisHealthProbe(JedisPooled redis) {
        this.redis = redis;
    }

    @Override
    public boolean isUp() {
        try {
            return "PONG".equalsIgnoreCase(redis.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
