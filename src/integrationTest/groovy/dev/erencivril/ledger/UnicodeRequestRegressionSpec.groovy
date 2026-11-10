package dev.erencivril.ledger

import dev.erencivril.ledger.cache.RedisClients
import dev.erencivril.ledger.support.ApiClient
import dev.erencivril.ledger.support.Infra
import groovy.json.JsonOutput
import groovy.sql.Sql
import spock.lang.Specification

class UnicodeRequestRegressionSpec extends Specification {
    def redis = RedisClients.connect(Infra.redisUrl())
    def sql = Sql.newInstance(Infra.jdbcUrl(), Infra.POSTGRES.username, Infra.POSTGRES.password, 'org.postgresql.Driver')
    def cleanup() { sql.close(); redis.close() }

    // Compare complete persisted rows, including balances, claims, audit, features and outbox.
    def databaseState() {
        sql.rows("select tablename from pg_tables where schemaname = 'public' order by tablename")
                .collectEntries { row ->
                    def table = row.tablename
                    [(table): sql.rows("select to_jsonb(t)::text value from \"${table}\" t order by 1".toString())*.value]
                }
    }
    def redisState() {
        redis.keys('*').sort().collectEntries { key -> [(key): redis.dump(key).encodeBase64().toString()] }
    }
    def wire(Map body, String escape) {
        JsonOutput.toJson(body).replace('Alice?Smith', 'Alice' + (char) 92 + escape + 'Smith')
    }

    def "malformed #escape cannot replay a #status transfer or change any state"() {
        given:
        def api = new ApiClient(RISK_ENABLED: true)
        def a = api.createAccount('USD', '10000.00')
        def b = api.createAccount('USD', null, 'Alice Smith')
        def headers = ['Idempotency-Key': UUID.randomUUID().toString()]
        def body = [sourceAccountId: a, destinationAccountId: b, amount: amount,
                    currency: 'USD', payeeName: 'Alice?Smith']
        def first = api.post('/transfers', body, headers)
        assert first.status == (status == 'COMPLETED' ? 201 : 202)
        assert first.body.status == status
        def dbBefore = databaseState()
        def redisBefore = redisState()

        when:
        def invalid = api.post('/transfers', wire(body, escape), headers)

        then:
        invalid.status == 400
        invalid.body.code == 'invalid_text'
        invalid.header('Idempotency-Replayed') != 'true'
        databaseState() == dbBefore
        redisState() == redisBefore

        when:
        def replay = api.post('/transfers', body, headers)

        then:
        replay.status == 200
        replay.header('Idempotency-Replayed') == 'true'
        replay.body == first.body
        databaseState() == dbBefore
        redisState() == redisBefore

        cleanup:
        api.close()

        where:
        amount    | status      | escape
        '1.00'    | 'COMPLETED' | 'ud800'
        '1.00'    | 'COMPLETED' | 'udc00'
        '1500.00' | 'PENDING'   | 'ud800'
        '1500.00' | 'PENDING'   | 'udc00'
    }

    def "valid supplementary names are accepted and retain exact identity for #status"() {
        given:
        def api = new ApiClient(RISK_ENABLED: true)
        def name = 'Alİce ' + new String(Character.toChars(0x1f600)) + ' Smith'
        def a = api.createAccount('USD', '10000.00')
        def b = api.createAccount('USD', null, name)
        def headers = ['Idempotency-Key': UUID.randomUUID().toString()]
        def body = [sourceAccountId: a, destinationAccountId: b, amount: amount, currency: 'USD', payeeName: name]

        when:
        def first = api.post('/transfers', body, headers)
        def same = api.post('/transfers', body, headers)
        def changed = api.post('/transfers', body + [payeeName: name + new String(Character.toChars(0x1f601))], headers)

        then:
        first.status == (status == 'COMPLETED' ? 201 : 202)
        first.body.status == status
        same.status == 200
        same.header('Idempotency-Replayed') == 'true'
        same.body == first.body
        changed.status == 409
        changed.body.code == 'idempotency_key_reused'

        cleanup:
        api.close()

        where:
        amount    | status
        '1.00'    | 'COMPLETED'
        '1500.00' | 'PENDING'
    }

    def "fresh malformed name #escape has no side effects and leaves the key usable"() {
        given:
        def api = new ApiClient(RISK_ENABLED: true)
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD', null, 'Alice Smith')
        def headers = ['Idempotency-Key': UUID.randomUUID().toString()]
        def body = [sourceAccountId: a, destinationAccountId: b, amount: '1.00', currency: 'USD', payeeName: 'Alice?Smith']
        def dbBefore = databaseState()
        def redisBefore = redisState()

        when:
        def invalid = api.post('/transfers', wire(body, escape), headers)

        then:
        invalid.status == 400
        invalid.body.code == 'invalid_text'
        databaseState() == dbBefore
        redisState() == redisBefore
        api.post('/transfers', body, headers).status == 201

        cleanup:
        api.close()

        where:
        escape << ['ud800', 'udc00']
    }

    def "required owner text rejects malformed #escape before persistence"() {
        given:
        def api = new ApiClient()
        def dbBefore = databaseState()
        def redisBefore = redisState()

        when:
        def invalid = api.post('/accounts', wire([owner: 'Alice?Smith', currency: 'USD'], escape))

        then:
        invalid.status == 400
        invalid.body.code == 'invalid_text'
        databaseState() == dbBefore
        redisState() == redisBefore

        cleanup:
        api.close()

        where:
        escape << ['ud800', 'udc00']
    }
}
