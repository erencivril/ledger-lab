package dev.erencivril.ledger.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * One PostgreSQL and one Redis container for the whole test run, started on first use.
 * Specs share them and isolate themselves by creating their own accounts.
 */
class Infra {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer('postgres:16-alpine')
            .withDatabaseName('ledger').withUsername('ledger').withPassword('ledger')
    static final GenericContainer<?> REDIS = new GenericContainer<>('redis:7-alpine').withExposedPorts(6379)

    static {
        POSTGRES.start()
        REDIS.start()
    }

    static String jdbcUrl() { POSTGRES.jdbcUrl }

    static String redisUrl() { "redis://${REDIS.host}:${REDIS.getMappedPort(6379)}" }
}
