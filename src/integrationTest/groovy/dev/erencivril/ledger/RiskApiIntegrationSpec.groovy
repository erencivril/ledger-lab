package dev.erencivril.ledger

import dev.erencivril.ledger.support.ApiClient
import spock.lang.Shared
import spock.lang.Specification

/** The risk checks and the hold endpoints over HTTP, with the rule set that ships with the application. */
class RiskApiIntegrationSpec extends Specification {

    @Shared ApiClient api

    def setupSpec() { api = new ApiClient([RISK_ENABLED: 'true', RISK_RULESET_VERSION: '1']) }
    def cleanupSpec() { api.close() }

    String newKey() { "api-risk-${UUID.randomUUID()}".toString() }

    def "a large first payment is held: 202, PENDING, and the reason codes are in the response"() {
        given:
        def a = api.createAccount('USD', '5000.00')
        def b = api.createAccount('USD')

        when:
        def r = api.transfer(newKey(), a, b, '1500.00')

        then:
        r.status == 202
        r.body.status == 'PENDING'
        r.body.risk.action == 'HOLD'
        r.body.risk.ruleSetVersion == 1
        r.body.risk.reasonCodes == ['FIRST_PAYMENT_LARGE', 'NEW_PAYEE_ACCOUNT']
        api.get("/accounts/$a").body.balance == '3500.00'
        api.get("/accounts/$b").body.balance == '0.00'
        api.get("/transfers/${r.body.id}").body == r.body
    }

    def "POST /transfers/{id}/release pays the payee and POST again is a 409"() {
        given:
        def a = api.createAccount('USD', '5000.00')
        def b = api.createAccount('USD')
        def held = api.transfer(newKey(), a, b, '1500.00').body.id

        when:
        def released = api.post("/transfers/$held/release", [:])

        then:
        released.status == 200
        released.body.status == 'COMPLETED'
        api.get("/accounts/$b").body.balance == '1500.00'

        when:
        def again = api.post("/transfers/$held/release", [:])
        def rejectAfter = api.post("/transfers/$held/reject", [:])

        then:
        again.status == 409
        again.body.code == 'transfer_not_pending'
        rejectAfter.status == 409
    }

    def "POST /transfers/{id}/reject refunds the sender"() {
        given:
        def a = api.createAccount('USD', '5000.00')
        def b = api.createAccount('USD')
        def held = api.transfer(newKey(), a, b, '1500.00').body.id

        when:
        def rejected = api.post("/transfers/$held/reject", [:])

        then:
        rejected.status == 200
        rejected.body.status == 'REJECTED'
        api.get("/accounts/$a").body.balance == '5000.00'
        api.get("/accounts/$b").body.balance == '0.00'
    }

    def "release and reject on a bad id: 404 for unknown, 400 for malformed"() {
        expect:
        api.post("/transfers/${UUID.randomUUID()}/release", [:]).with { status == 404 && body.code == 'transfer_not_found' }
        api.post('/transfers/not-a-uuid/reject', [:]).with { status == 400 && body.code == 'invalid_id' }
    }

    def "a small payment is allowed and the response still says so"() {
        given:
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')

        when:
        def r = api.transfer(newKey(), a, b, '20.00')

        then:
        r.status == 201
        r.body.status == 'COMPLETED'
        r.body.risk == [action: 'ALLOW', ruleSetVersion: 1, reasonCodes: []]
    }

    def "a payment far above the sender's habit needs step-up: 403 first, then 201 with stepUpConfirmed"() {
        given:
        def a = api.createAccount('USD', '5000.00')
        def b = api.createAccount('USD')
        (1..3).each { api.transfer(newKey(), a, b, '10.00') }
        def key = newKey()

        when:
        def refused = api.transfer(key, a, b, '600.00')

        then:
        refused.status == 403
        refused.body.code == 'step_up_required'
        refused.body.reasonCodes == ['AMOUNT_DEVIATION', 'NEW_PAYEE_ACCOUNT']
        api.get("/accounts/$a").body.balance == '4970.00'

        when:
        def confirmed = api.transfer(key, a, b, '600.00', 'USD', true)

        then:
        confirmed.status == 201
        confirmed.body.risk.action == 'WARN'
        api.get("/accounts/$a").body.balance == '4370.00'
    }

    def "stepUpConfirmed must be a boolean"() {
        given:
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')

        when:
        def r = api.post('/transfers', [sourceAccountId: a, destinationAccountId: b, amount: '1.00', currency: 'USD', stepUpConfirmed: 'yes'],
                ['Idempotency-Key': newKey()])

        then:
        r.status == 400
        r.body.code == 'invalid_field'
    }

    def "with RISK_ENABLED=false nothing is held and there is no risk block"() {
        given:
        def off = new ApiClient([RISK_ENABLED: 'false'])
        def a = off.createAccount('USD', '5000.00')
        def b = off.createAccount('USD')

        when:
        def r = off.transfer(newKey(), a, b, '4000.00')

        then:
        r.status == 201
        r.body.status == 'COMPLETED'
        r.body.risk == null

        cleanup:
        off?.close()
    }
}
