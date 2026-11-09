package dev.erencivril.ledger

import dev.erencivril.ledger.cache.RedisRiskVelocity
import dev.erencivril.ledger.support.Infra
import dev.erencivril.ledger.support.ApiClient
import dev.erencivril.ledger.domain.risk.*
import redis.clients.jedis.JedisPooled
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

class RiskSignalsIntegrationSpec extends Specification {

    def "Redis sliding windows expire old attempts, and retain count and amount independently"() {
        given:
        def pool = new JedisPooled('127.0.0.1', Infra.REDIS.getMappedPort(6379))
        def sender = UUID.randomUUID()
        def key = "risk:velocity:$sender".toString()
        long now = Long.parseLong((pool.eval("return redis.call('TIME')") as List)[0].toString()) * 1000
        pool.zadd(key, now - 86400000 - 5000, 'expired|90000')
        pool.zadd(key, now - 3600000 - 5000, 'day-only|3000')
        pool.zadd(key, now - 10000, 'recent|2000')

        when:
        def snapshot = new RedisRiskVelocity(pool).record(sender, 10.25G)

        then:
        snapshot.available()
        snapshot.count1h() == 2
        snapshot.amount1h() == 30.25G
        snapshot.count24h() == 3
        snapshot.amount24h() == 60.25G
        pool.zcard(key) == 3
        pool.ttl(key) >= 86395L
        pool.ttl(key) <= 86401L

        cleanup:
        pool?.close()
    }

    def "parallel risk checks do not lose attempts or amounts"() {
        given:
        def pool = new JedisPooled('127.0.0.1', Infra.REDIS.getMappedPort(6379))
        def sender = UUID.randomUUID()
        def velocity = new RedisRiskVelocity(pool)

        when:
        def futures = (1..20).collect { CompletableFuture.supplyAsync { velocity.record(sender, 2.50G) } }
        futures*.join()
        def last = velocity.record(sender, 1G)

        then:
        last.count1h() == 21
        last.amount1h() == 51G
        last.count24h() == 21
        last.amount24h() == 51G

        cleanup:
        pool?.close()
    }

    def "Redis down is not a zero velocity: the engine holds even if rules are empty"() {
        given:
        def pool = new JedisPooled('127.0.0.1', 1)

        when:
        def v = new RedisRiskVelocity(pool).record(UUID.randomUUID(), 10G)
        def c = new RiskContext(new dev.erencivril.ledger.domain.Money(10G, dev.erencivril.ledger.domain.Currency.USD),
                10G, false, java.time.Duration.ofDays(10), 0, null, true,
                NameMatcher.check(null, 'x'), false, v)
        def d = new RiskEngine(1, [], 0, 0).assess(c)

        then:
        !v.available()
        d.action() == RiskAction.HOLD
        d.reasonCodes() == ['VELOCITY_UNAVAILABLE']

        cleanup:
        pool?.close()
    }

    def "version 2 checks actual account owner for mock sanctions and supplied name for mismatch"() {
        given:
        def api = new ApiClient([RISK_ENABLED: 'true', RISK_RULESET_VERSION: '2'])
        def sender = api.createAccount('USD', '1000.00')
        def sanctioned = api.createAccount('USD', null, 'Example Restricted Person')
        def payee = api.createAccount('USD', null, 'Élodie Martin')

        when:
        def blocked = api.transfer(UUID.randomUUID().toString(), sender, sanctioned, '10.00')
        def mismatch = api.post('/transfers', [sourceAccountId: sender, destinationAccountId: payee,
                amount: '10.00', currency: 'USD', payeeName: 'Bob Jones'], ['Idempotency-Key': UUID.randomUUID().toString()])
        def match = api.post('/transfers', [sourceAccountId: sender, destinationAccountId: payee,
                amount: '10.00', currency: 'USD', payeeName: 'Elodie Martin'], ['Idempotency-Key': UUID.randomUUID().toString()])

        then:
        blocked.status == 403
        blocked.body.code == 'blocked_by_risk'
        blocked.body.reasonCodes == ['MOCK_SANCTIONS']
        mismatch.status == 403
        mismatch.body.reasonCodes == ['NAME_NO_MATCH']
        match.status == 201
        match.body.risk.action == 'ALLOW'

        cleanup:
        api?.close()
    }

    def "the tenth small payment in an hour is held before the payee receives it"() {
        given:
        def api = new ApiClient([RISK_ENABLED: 'true', RISK_RULESET_VERSION: '2'])
        def sender = api.createAccount('USD', '1000.00')
        def payee = api.createAccount('USD')
        (1..9).each { assert api.transfer(UUID.randomUUID().toString(), sender, payee, '1.00').status == 201 }

        when:
        def held = api.transfer(UUID.randomUUID().toString(), sender, payee, '1.00')

        then:
        held.status == 202
        held.body.risk.reasonCodes == ['VELOCITY_1H']
        api.get("/accounts/$payee").body.balance == '9.00'
        api.get("/accounts/$sender").body.balance == '990.00'

        cleanup:
        api?.close()
    }
    def "risk enabled with Redis down parks money rather than paying unchecked"() {
        given:
        def api = new ApiClient([RISK_ENABLED: 'true', REDIS_URL: 'redis://127.0.0.1:1'])
        def sender = api.createAccount('USD', '100.00')
        def payee = api.createAccount('USD')
        def key = UUID.randomUUID().toString()

        when:
        def held = api.transfer(key, sender, payee, '10.00')
        def replay = api.transfer(key, sender, payee, '10.00')

        then:
        held.status == 202
        held.body.risk.reasonCodes == ['VELOCITY_UNAVAILABLE']
        replay.status == 200
        replay.body == held.body
        api.get("/accounts/$payee").body.balance == '0.00'
        api.get("/accounts/$sender").body.balance == '90.00'

        cleanup:
        api?.close()
    }
}
