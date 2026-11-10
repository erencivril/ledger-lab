package dev.erencivril.ledger

import dev.erencivril.ledger.cache.RedisClients
import dev.erencivril.ledger.support.ApiClient
import dev.erencivril.ledger.support.Infra
import spock.lang.Specification

class CanonicalReplayRegressionSpec extends Specification {
    def "Postgres binding wins over wrong existing pointers, forged keys and mismatched hashes"() {
        given:
        def redis = RedisClients.connect(Infra.redisUrl())
        def api = new ApiClient()
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        def k = UUID.randomUUID().toString()
        def otherKey = UUID.randomUUID().toString()
        def first = api.transfer(k, a, b, '1.00')
        def other = api.transfer(otherKey, a, b, '2.00')
        def hash = redis.get("idem:$k").split(':')[0]
        def fresh = UUID.randomUUID().toString()

        when:
        redis.set("idem:$k", "$hash:${other.body.id}".toString())
        def replay = api.transfer(k, a, b, '1.00')

        then:
        replay.status == 200
        replay.body == first.body

        when:
        redis.set("idem:$fresh", "$hash:${other.body.id}".toString())
        def unclaimed = api.transfer(fresh, a, b, '1.00')

        then:
        unclaimed.status == 201
        unclaimed.body.id != other.body.id
        unclaimed.body.id != first.body.id

        when:
        redis.set("idem:$k", "wrong-hash:${other.body.id}".toString())
        def stale = api.transfer(k, a, b, '1.00')

        then:
        stale.status == 200
        stale.body == first.body
        api.get("/accounts/$a").body.balance == '96.00'

        cleanup:
        api.close()
        redis.close()
    }

    def "committed risk requests bind name and confirmation, not decimal spelling"() {
        given:
        def api = new ApiClient(RISK_ENABLED: true)
        def a = api.createAccount('USD', '10000.00')
        def b = api.createAccount('USD', null, 'ordinary')
        def k = UUID.randomUUID().toString()
        def body = [sourceAccountId: a, destinationAccountId: b, amount: amount, currency: 'USD', payeeName: 'ordinary']
        def headers = ['Idempotency-Key': k]

        when:
        def first = api.post('/transfers', body, headers)
        def same = api.post('/transfers', body + [amount: amount + '0'], headers)
        def changed = api.post('/transfers', body + [payeeName: 'different'], headers)
        def confirmation = api.post('/transfers', body + [stepUpConfirmed: true], headers)

        then:
        first.status == (status == 'PENDING' ? 202 : 201)
        first.body.status == status
        same.status == 200
        same.body == first.body
        changed.status == 409
        confirmation.status == 409

        cleanup:
        api.close()

        where:
        amount   | status
        '1.00'   | 'COMPLETED'
        '1500.00' | 'PENDING'
    }
}
