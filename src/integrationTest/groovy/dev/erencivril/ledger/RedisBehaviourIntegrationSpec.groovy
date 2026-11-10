package dev.erencivril.ledger

import dev.erencivril.ledger.application.ApplicationException
import dev.erencivril.ledger.cache.RedisClients
import dev.erencivril.ledger.cache.RedisIdempotencyCache
import dev.erencivril.ledger.cache.RedisVelocityLimiter
import dev.erencivril.ledger.support.ApiClient
import dev.erencivril.ledger.support.Infra
import spock.lang.Shared
import spock.lang.Specification

/** What Redis is used for, and what happens when it is wrong or missing. */
class RedisBehaviourIntegrationSpec extends Specification {

    @Shared def redis = RedisClients.connect(Infra.redisUrl())

    def cleanupSpec() { redis.close() }

    static String newKey() { 'rb-' + UUID.randomUUID() }

    // ---- velocity limiter ----------------------------------------------------------------

    def "the limiter allows N acquisitions per window and rejects the next with a retry hint"() {
        given:
        def limiter = new RedisVelocityLimiter(redis, 3, 60, true)
        def account = UUID.randomUUID()

        when:
        3.times { limiter.acquire(account) }

        then:
        noExceptionThrown()

        when:
        limiter.acquire(account)

        then:
        def e = thrown(ApplicationException.VelocityLimitExceeded)
        e.code() == 'velocity_limit_exceeded'
        e.retryAfterSeconds().intValue() in 1..60
    }

    def "the limit is per source account"() {
        given:
        def limiter = new RedisVelocityLimiter(redis, 1, 60, true)

        when:
        limiter.acquire(UUID.randomUUID())
        limiter.acquire(UUID.randomUUID())

        then:
        noExceptionThrown()
    }

    def "the window slides: old transfers stop counting"() {
        given:
        def limiter = new RedisVelocityLimiter(redis, 2, 1, true)
        def account = UUID.randomUUID()
        2.times { limiter.acquire(account) }

        when:
        limiter.acquire(account)

        then:
        thrown(ApplicationException.VelocityLimitExceeded)

        when:
        Thread.sleep(1200)
        limiter.acquire(account)

        then:
        noExceptionThrown()
    }

    def "under 60 parallel callers exactly N are admitted"() {
        given:
        def limiter = new RedisVelocityLimiter(redis, 10, 60, true)
        def account = UUID.randomUUID()
        def pool = java.util.concurrent.Executors.newFixedThreadPool(20)

        when:
        def outcomes = (1..60).collect {
            pool.submit({
                try { limiter.acquire(account); 'ok' } catch (ApplicationException.VelocityLimitExceeded e) { 'limited' }
            } as java.util.concurrent.Callable)
        }*.get()

        then:
        outcomes.count { it == 'ok' } == 10
        outcomes.count { it == 'limited' } == 50

        cleanup:
        pool.shutdown()
    }

    def "the limiter validates its settings"() {
        when:
        new RedisVelocityLimiter(redis, max, window, true)

        then:
        thrown(IllegalArgumentException)

        where:
        max | window
        0   | 60
        5   | 0
    }

    // ---- HTTP: 429 ------------------------------------------------------------------------

    def "the API answers 429 with Retry-After once a source account passes the velocity limit"() {
        given:
        def api = new ApiClient(VELOCITY_MAX_TRANSFERS: 3, VELOCITY_WINDOW_SECONDS: 60)
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        def other = api.createAccount('USD', '100.00')

        when:
        def first3 = (1..3).collect { api.transfer(newKey(), a, b, '1.00') }
        def fourth = api.transfer(newKey(), a, b, '1.00')

        then:
        first3*.status == [201, 201, 201]
        fourth.status == 429
        fourth.body.code == 'velocity_limit_exceeded'
        fourth.body.message.contains('3 transfers')
        fourth.header('Retry-After').toInteger() in 1..60
        api.get("/accounts/$a").body.balance == '97.00'

        and: 'other accounts are not affected'
        api.transfer(newKey(), other, b, '1.00').status == 201

        cleanup:
        api.close()
    }

    def "replaying an already-completed transfer is not counted against the limit"() {
        given:
        def api = new ApiClient(VELOCITY_MAX_TRANSFERS: 1)
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        def key = newKey()

        when:
        def first = api.transfer(key, a, b, '1.00')
        def replay = api.transfer(key, a, b, '1.00')
        def fresh = api.transfer(newKey(), a, b, '1.00')

        then:
        first.status == 201
        replay.status == 200
        fresh.status == 429

        cleanup:
        api.close()
    }

    // ---- HTTP: Redis is a cache, not a source of truth ---------------------------------------

    def "a replay is served even if the Redis cache entry is gone"() {
        given:
        def api = new ApiClient()
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        def key = newKey()
        def first = api.transfer(key, a, b, '1.00')

        expect: 'the first request filled the cache'
        redis.get("idem:$key") != null

        when:
        redis.del("idem:$key")
        def replay = api.transfer(key, a, b, '1.00')

        then:
        replay.status == 200
        replay.body == first.body

        cleanup:
        api.close()
    }

    def "a poisoned cache entry cannot make the API repeat or invent a transfer"() {
        given:
        def api = new ApiClient()
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        def key = newKey()
        def first = api.transfer(key, a, b, '1.00')

        when: 'the cache points at a transfer that does not exist'
        redis.set("idem:$key", "garbage:${UUID.randomUUID()}")
        def stale = api.transfer(key, a, b, '1.00')

        then: 'Postgres wins even when the cached fingerprint differs'
        stale.status == 200
        stale.body == first.body

        when: 'the cache has the right fingerprint but a missing transfer'
        def fingerprint = redis.get("idem:$key").split(':')[0]
        def realFingerprintRequest = new dev.erencivril.ledger.application.TransferService.TransferCommand(
                key, UUID.fromString(a), UUID.fromString(b),
                new dev.erencivril.ledger.domain.Money(new BigDecimal('1.00'), dev.erencivril.ledger.domain.Currency.USD))
        redis.set("idem:$key", "${realFingerprintRequest.fingerprint()}:${UUID.randomUUID()}")
        def fallback = api.transfer(key, a, b, '1.00')

        then: 'it falls back to Postgres and returns the real transfer'
        fallback.status == 200
        fallback.body == first.body
        api.get("/accounts/$a").body.balance == '99.00'

        cleanup:
        api.close()
    }

    def "garbage in the cache is ignored"() {
        given:
        def cache = new RedisIdempotencyCache(redis)
        def key = newKey()

        expect:
        cache.get(key).isEmpty()

        when:
        redis.set("idem:$key", 'no-separator')

        then:
        cache.get(key).isEmpty()
    }

    def "with Redis unreachable, transfers still work, replays still dedupe via Postgres and health is degraded"() {
        given:
        def api = new ApiClient(REDIS_URL: 'redis://127.0.0.1:1')
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        def key = newKey()

        when:
        def first = api.transfer(key, a, b, '1.00')
        def replay = api.transfer(key, a, b, '1.00')
        def conflict = api.transfer(key, a, b, '2.00')
        def health = api.get('/health')

        then:
        first.status == 201
        replay.status == 200
        replay.body == first.body
        conflict.status == 409
        api.get("/accounts/$a").body.balance == '99.00'
        health.status == 200
        health.body == [status: 'degraded', postgres: 'up', redis: 'down']

        cleanup:
        api.close()
    }

    def "with Redis unreachable and fail-open disabled, transfers are refused instead of unchecked"() {
        given:
        def api = new ApiClient(REDIS_URL: 'redis://127.0.0.1:1', VELOCITY_FAIL_OPEN: false)
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')

        when:
        def r = api.transfer(newKey(), a, b, '1.00')

        then:
        r.status == 500
        r.body.code == 'internal_error'
        api.get("/accounts/$a").body.balance == '100.00'

        cleanup:
        api.close()
    }
}
