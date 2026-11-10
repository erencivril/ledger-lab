package dev.erencivril.ledger

import dev.erencivril.ledger.cache.RedisClients
import dev.erencivril.ledger.cache.RedisRiskVelocity
import dev.erencivril.ledger.support.ApiClient
import dev.erencivril.ledger.support.Infra
import groovy.sql.Sql
import redis.clients.jedis.exceptions.JedisDataException
import spock.lang.Specification

class RequestValidationRegressionSpec extends Specification {
    def redis = RedisClients.connect(Infra.redisUrl())
    def sql = Sql.newInstance(Infra.jdbcUrl(), Infra.POSTGRES.username, Infra.POSTGRES.password, 'org.postgresql.Driver')
    def cleanup() { sql.close(); redis.close() }
    def auditCount() { sql.firstRow('select count(*) c from risk_decisions').c }

    def "risk-enabled invalid amounts never enter Redis and the next valid payment completes"() {
        given:
        def api = new ApiClient(RISK_ENABLED: true)
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        def before = auditCount()

        expect:
        ['-1.00', '0.00', '1000000000000.01'].each { amount ->
            def r = api.transfer(UUID.randomUUID().toString(), a, b, amount)
            assert r.status == 400
            assert r.body.code == 'invalid_amount'
            assert auditCount() == before
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
        def before = auditCount()

        when:
        def r = api.post('/transfers', [sourceAccountId: source, destinationAccountId: b,
                amount: '1.00', currency: 'USD', payeeName: name], ['Idempotency-Key': UUID.randomUUID().toString()])

        then:
        r.status == 404
        r.body.code == 'account_not_found'
        auditCount() == before
        redis.zcard("risk:velocity:$source".toString()) == 0

        cleanup:
        api.close()

        where:
        owner          | name
        'Synthetic Blocked Entity' | 'Synthetic Blocked Entity'
        'ordinary'     | 'different name'
    }


    def "Lua rejects malformed amounts before any writes, including retained corrupt members"() {
        given:
        def field = RedisRiskVelocity.getDeclaredField('SCRIPT')
        field.accessible = true
        def script = field.get(null) as String
        def key = "risk:velocity:${UUID.randomUUID()}".toString()

        expect:
        ['-100', '0', '1.5', 'not-a-number', '9000000000000001'].each { cents ->
            try {
                redis.eval(script, [key], [UUID.randomUUID().toString(), cents])
                assert false: 'Lua accepted malformed amount'
            } catch (JedisDataException expected) {
                assert expected.message.contains('invalid amount')
            }
            assert redis.exists(key) == false
        }

        when:
        redis.zadd(key, 1d, 'corrupt|-100')
        redis.eval(script, [key], [UUID.randomUUID().toString(), '100'])

        then:
        def error = thrown(JedisDataException)
        error.message.contains('invalid stored amount')
        redis.zrange(key, 0, -1) == ['corrupt|-100']
        redis.ttl(key) == -1
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
