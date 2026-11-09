package dev.erencivril.ledger

import dev.erencivril.ledger.application.LockStrategyKind
import spock.lang.Specification

class ConfigSpec extends Specification {

    def "the defaults are the conservative ones: pessimistic locking, five attempts"() {
        when:
        def config = Config.fromEnv([:])

        then:
        config.lockStrategy() == LockStrategyKind.PESSIMISTIC
        config.transferMaxAttempts() == 5
        config.transferRetryBaseMillis() == 5
        config.databaseConnectTimeoutMillis() == 10_000
        config.databasePoolSize() == 10
        config.velocityFailOpen()
    }

    def "every setting can be overridden from the environment"() {
        when:
        def config = Config.fromEnv([
                LOCK_STRATEGY               : 'atomic',
                TRANSFER_MAX_ATTEMPTS       : '9',
                TRANSFER_RETRY_BASE_MILLIS  : '2',
                DATABASE_CONNECT_TIMEOUT_MS : '1500',
        ])

        then:
        config.lockStrategy() == LockStrategyKind.ATOMIC
        config.transferMaxAttempts() == 9
        config.transferRetryBaseMillis() == 2
        config.databaseConnectTimeoutMillis() == 1500
    }

    def "a blank value falls back to the default"() {
        expect:
        Config.fromEnv([LOCK_STRATEGY: ' ', TRANSFER_MAX_ATTEMPTS: '']).lockStrategy() == LockStrategyKind.PESSIMISTIC
        Config.fromEnv([TRANSFER_MAX_ATTEMPTS: '']).transferMaxAttempts() == 5
    }

    def "an unknown lock strategy stops the application from starting"() {
        when:
        Config.fromEnv([LOCK_STRATEGY: 'row'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('pessimistic')
    }

    def "a number that is not a number is refused with the name of the setting"() {
        when:
        Config.fromEnv([TRANSFER_MAX_ATTEMPTS: 'many'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('TRANSFER_MAX_ATTEMPTS')
    }
}
