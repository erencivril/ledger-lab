package dev.erencivril.ledger

import dev.erencivril.ledger.cache.RedisClients
import dev.erencivril.ledger.cache.RedisRiskVelocity
import dev.erencivril.ledger.support.ApiClient
import dev.erencivril.ledger.support.Infra
import spock.lang.Specification

class RequestValidationRegressionSpec extends Specification {
    def redis = RedisClients.connect(Infra.redisUrl())
    def cleanup() { redis.close() }

    def "risk-enabled invalid amounts never enter Redis and the next valid payment completes"() {
        given:
        def api = new ApiClient(RISK_ENABLED: true)
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')

        expect:
        ['-1.00', '0.00', '1000000000000.01'].each { amount ->
            def r = api.transfer(UUID.randomUUID().toString(), a, b, amount)
            assert r.status == 400
            assert r.body.code == 'invalid_amount'
            assert redis.zcard("risk:velocity:$a".toString()) == 0
            assert redis.keys("velocity:*$a*".toString()).isEmpty()
        }
        def good = api.transfer(UUID.randomUUID().toString(), a, b, '1.00')
        good.status == 201
        good.body.status == 'COMPLETED'
        redis.zcard("risk:velocity:$a".toString()) == 1

        cleanup:
        api.close()
    }

    def "unknown source is a 404 before sanctions or name checks"() {
        given:
        def api = new ApiClient(RISK_ENABLED: true)
        def b = api.createAccount('USD', null, owner)
        def source = UUID.randomUUID().toString()

        when:
        def r = api.post('/transfers', [sourceAccountId: source, destinationAccountId: b,
                amount: '1.00', currency: 'USD', payeeName: name], ['Idempotency-Key': UUID.randomUUID().toString()])

        then:
        r.status == 404
        r.body.code == 'account_not_found'
        redis.zcard("risk:velocity:$source".toString()) == 0

        cleanup:
        api.close()

        where:
        owner          | name
        'Evil Corp'    | 'Evil Corp'
        'ordinary'     | 'different name'
    }

    def "velocity adapter rejects malformed amounts without writing"() {
        given:
        def sender = UUID.randomUUID()
        def adapter = new RedisRiskVelocity(redis)

        expect:
        [-1G, 0G, 0.001G, 100000000000000G].each { amount ->
            assert !adapter.record(sender, amount).available()
            assert redis.zcard("risk:velocity:$sender".toString()) == 0
        }
        adapter.record(sender, 1.00G).available()
        redis.zcard("risk:velocity:$sender".toString()) == 1
    }
}
