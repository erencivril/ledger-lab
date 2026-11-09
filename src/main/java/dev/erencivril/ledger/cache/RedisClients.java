package dev.erencivril.ledger.cache;

import java.net.URI;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

public final class RedisClients {

    /** Short timeouts on purpose: a slow Redis must never make a transfer slow. */
    private static final int TIMEOUT_MILLIS = 250;

    private RedisClients() {}

    /** Connects lazily, so the service starts even when Redis is not reachable yet. */
    public static JedisPooled connect(String redisUrl) {
        URI uri = URI.create(redisUrl);
        int port = uri.getPort() == -1 ? 6379 : uri.getPort();
        GenericObjectPoolConfig<Connection> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(32);
        pool.setMaxWaitMillis(TIMEOUT_MILLIS);
        return new JedisPooled(
                pool,
                new HostAndPort(uri.getHost(), port),
                DefaultJedisClientConfig.builder()
                        .connectionTimeoutMillis(TIMEOUT_MILLIS)
                        .socketTimeoutMillis(TIMEOUT_MILLIS)
                        .build());
    }
}
