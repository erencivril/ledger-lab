package dev.erencivril.ledger

import dev.erencivril.ledger.support.ApiClient
import spock.lang.Shared
import spock.lang.Specification

class ApiIntegrationSpec extends Specification {

    @Shared ApiClient api

    def setupSpec() { api = new ApiClient() }
    def cleanupSpec() { api.close() }

    static String newKey() { 'it-' + UUID.randomUUID() }

    // ---- accounts -------------------------------------------------------------------------

    def "POST /accounts creates an account and GET returns it"() {
        when:
        def created = api.post('/accounts', [owner: 'Eren', currency: 'USD', initialBalance: '100.5'])

        then:
        created.status == 201
        created.header('Content-Type').startsWith('application/json')
        created.header('Location') == "/accounts/${created.body.id}"
        created.body.owner == 'Eren'
        created.body.currency == 'USD'
        created.body.balance == '100.50'

        when:
        def fetched = api.get("/accounts/${created.body.id}")

        then:
        fetched.status == 200
        fetched.body == created.body
    }

    def "an account may be opened without a balance, and with a number instead of a string"() {
        expect:
        api.post('/accounts', [owner: 'a', currency: 'EUR']).body.balance == '0.00'
        api.post('/accounts', [owner: 'a', currency: 'JPY', initialBalance: 500]).body.balance == '500'
        api.post('/accounts', '{"owner":"a","currency":"KWD","initialBalance":1.250}').body.balance == '1.250'
    }

    def "POST /accounts rejects bad input with 400 and a {code, message} body: #label"() {
        when:
        def r = api.post('/accounts', body)

        then:
        r.status == 400
        r.body.code == code
        r.body.message

        where:
        label                | body                                                             | code
        'not json'           | 'nope'                                                           | 'invalid_json'
        'json array'         | '[1]'                                                            | 'invalid_json'
        'empty body'         | ''                                                               | 'invalid_json'
        'missing owner'      | [currency: 'USD']                                                | 'missing_field'
        'missing currency'   | [owner: 'a']                                                     | 'missing_field'
        'owner not a string' | [owner: 5, currency: 'USD']                                      | 'invalid_field'
        'blank owner'        | [owner: ' ', currency: 'USD']                                    | 'invalid_owner'
        'lowercase currency' | [owner: 'a', currency: 'usd']                                    | 'unsupported_currency'
        'unknown currency'   | [owner: 'a', currency: 'XYZ']                                    | 'unsupported_currency'
        'too many decimals'  | [owner: 'a', currency: 'USD', initialBalance: '1.001']           | 'invalid_amount'
        'negative balance'   | [owner: 'a', currency: 'USD', initialBalance: '-1.00']           | 'invalid_amount'
        'balance not number' | [owner: 'a', currency: 'USD', initialBalance: 'lots']            | 'invalid_amount'
    }

    def "GET /accounts/{id}: unknown is 404, malformed is 400"() {
        expect:
        api.get("/accounts/${UUID.randomUUID()}").with { status == 404 && body.code == 'account_not_found' }
        api.get('/accounts/not-a-uuid').with { status == 400 && body.code == 'invalid_id' }
    }

    // ---- transfers ------------------------------------------------------------------------

    def "POST /transfers moves money and GET /transfers/{id} returns the same transfer"() {
        given:
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD', '5.00')
        def key = newKey()

        when:
        def r = api.transfer(key, a, b, '30.25')

        then:
        r.status == 201
        r.header('Idempotency-Replayed') == 'false'
        r.header('Location') == "/transfers/${r.body.id}"
        r.body.kind == 'TRANSFER'
        r.body.sourceAmount == [amount: '30.25', currency: 'USD']
        r.body.destinationAmount == [amount: '30.25', currency: 'USD']
        r.body.fxRate == null
        api.get("/accounts/$a").body.balance == '69.75'
        api.get("/accounts/$b").body.balance == '35.25'
        api.get("/transfers/${r.body.id}").body == r.body
    }

    def "a cross-currency transfer returns the converted amount and the rate that was applied"() {
        given:
        def usd = api.createAccount('USD', '100.00')
        def jpy = api.createAccount('JPY')

        when:
        def r = api.transfer(newKey(), usd, jpy, '10.00', 'USD')

        then:
        r.status == 201
        r.body.sourceAmount == [amount: '10.00', currency: 'USD']
        r.body.destinationAmount == [amount: '1512', currency: 'JPY']
        r.body.fxRate == '151.20000000'
        api.get("/accounts/$jpy").body.balance == '1512'
    }

    def "retrying with the same Idempotency-Key and body returns 200 with the original transfer"() {
        given:
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        def key = newKey()

        when:
        def first = api.transfer(key, a, b, '10.00')
        def retry = api.transfer(key, a, b, '10.00')
        def retryWrittenDifferently = api.transfer(key, a, b, '10')

        then:
        first.status == 201
        retry.status == 200
        retry.header('Idempotency-Replayed') == 'true'
        retry.body == first.body
        retryWrittenDifferently.status == 200
        retryWrittenDifferently.body == first.body
        api.get("/accounts/$a").body.balance == '90.00'
    }

    def "the same key with a different body is 409 and moves nothing"() {
        given:
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        def key = newKey()
        api.transfer(key, a, b, '10.00')

        when:
        def r = api.transfer(key, a, b, '11.00')

        then:
        r.status == 409
        r.body.code == 'idempotency_key_reused'
        api.get("/accounts/$a").body.balance == '90.00'
    }

    def "the Idempotency-Key header is required and limited in length"() {
        given:
        def a = api.createAccount('USD', '10.00')
        def b = api.createAccount('USD')

        expect:
        api.transfer(null, a, b, '1.00').with { status == 400 && body.code == 'missing_idempotency_key' }
        api.transfer(' ', a, b, '1.00').with { status == 400 && body.code == 'missing_idempotency_key' }
        api.transfer('k' * 256, a, b, '1.00').with { status == 400 && body.code == 'invalid_idempotency_key' }
        api.transfer('k' * 255, a, b, '1.00').status == 201
    }

    def "POST /transfers validates the body: #label"() {
        given:
        def a = api.createAccount('USD', '10.00')
        def b = api.createAccount('USD')

        when:
        def r = api.post('/transfers', body(a, b), ['Idempotency-Key': newKey()])

        then:
        r.status == status
        r.body.code == code

        where:
        label                | body                                                                                              | status | code
        'missing amount'     | { s, d -> [sourceAccountId: s, destinationAccountId: d, currency: 'USD'] }                         | 400    | 'missing_field'
        'missing currency'   | { s, d -> [sourceAccountId: s, destinationAccountId: d, amount: '1.00'] }                          | 400    | 'missing_field'
        'bad source id'      | { s, d -> [sourceAccountId: 'x', destinationAccountId: d, amount: '1.00', currency: 'USD'] }       | 400    | 'invalid_id'
        'amount not numeric' | { s, d -> [sourceAccountId: s, destinationAccountId: d, amount: 'ten', currency: 'USD'] }          | 400    | 'invalid_amount'
        'too many decimals'  | { s, d -> [sourceAccountId: s, destinationAccountId: d, amount: '1.005', currency: 'USD'] }        | 400    | 'invalid_amount'
        'unsupported ccy'    | { s, d -> [sourceAccountId: s, destinationAccountId: d, amount: '1.00', currency: 'ABC'] }         | 400    | 'unsupported_currency'
        'zero amount'        | { s, d -> [sourceAccountId: s, destinationAccountId: d, amount: '0.00', currency: 'USD'] }         | 400    | 'invalid_amount'
        'negative amount'    | { s, d -> [sourceAccountId: s, destinationAccountId: d, amount: '-5.00', currency: 'USD'] }        | 400    | 'invalid_amount'
        'same account'       | { s, d -> [sourceAccountId: s, destinationAccountId: s, amount: '1.00', currency: 'USD'] }         | 422    | 'same_account'
        'insufficient funds' | { s, d -> [sourceAccountId: s, destinationAccountId: d, amount: '10.01', currency: 'USD'] }        | 422    | 'insufficient_funds'
        'wrong currency'     | { s, d -> [sourceAccountId: s, destinationAccountId: d, amount: '1.00', currency: 'EUR'] }         | 422    | 'currency_mismatch'
        'unknown source'     | { s, d -> [sourceAccountId: UUID.randomUUID(), destinationAccountId: d, amount: '1', currency: 'USD'] } | 404 | 'account_not_found'
        'unknown dest'       | { s, d -> [sourceAccountId: s, destinationAccountId: UUID.randomUUID(), amount: '1', currency: 'USD'] } | 404 | 'account_not_found'
    }

    def "a rejected transfer does not consume its key, so the client can fix the request and reuse it"() {
        given:
        def a = api.createAccount('USD', '5.00')
        def b = api.createAccount('USD')
        def key = newKey()

        expect:
        api.transfer(key, a, b, '50.00').status == 422
        api.transfer(key, a, b, '5.00').status == 201
    }

    def "GET /transfers/{id}: unknown is 404, malformed is 400"() {
        expect:
        api.get("/transfers/${UUID.randomUUID()}").with { status == 404 && body.code == 'transfer_not_found' }
        api.get('/transfers/zzz').with { status == 400 && body.code == 'invalid_id' }
    }

    // ---- statement ------------------------------------------------------------------------

    def "GET /accounts/{id}/statement lists journal entries newest first and pages with a cursor"() {
        given:
        def a = api.createAccount('USD', '100.00')
        def b = api.createAccount('USD')
        (1..5).each { api.transfer(newKey(), a, b, "${it}.00") }

        when:
        def page1 = api.get("/accounts/$a/statement?limit=2")
        def page2 = api.get("/accounts/$a/statement?limit=2&cursor=${page1.body.nextCursor}")
        def page3 = api.get("/accounts/$a/statement?limit=2&cursor=${page2.body.nextCursor}")

        then:
        page1.status == 200
        page1.body.accountId == a
        page1.body.entries*.amount*.amount == ['-5.00', '-4.00']
        page1.body.entries*.balanceAfter*.amount == ['85.00', '90.00']
        page2.body.entries*.amount*.amount == ['-3.00', '-2.00']
        page3.body.entries*.amount*.amount == ['-1.00', '100.00']
        page3.body.nextCursor == null
        page1.body.entries.every { it.transferId && it.createdAt && it.amount.currency == 'USD' }

        and: 'the receiver sees the credits'
        api.get("/accounts/$b/statement").body.entries*.amount*.amount == ['5.00', '4.00', '3.00', '2.00', '1.00']
    }

    def "statement: default page size, bad paging parameters and unknown account"() {
        given:
        def a = api.createAccount('USD', '1.00')

        expect:
        api.get("/accounts/$a/statement").body.entries.size() == 1
        api.get("/accounts/$a/statement?limit=0").with { status == 400 && body.code == 'invalid_limit' }
        api.get("/accounts/$a/statement?limit=101").with { status == 400 && body.code == 'invalid_limit' }
        api.get("/accounts/$a/statement?limit=abc").with { status == 400 && body.code == 'invalid_limit' }
        api.get("/accounts/$a/statement?cursor=-1").with { status == 400 && body.code == 'invalid_cursor' }
        api.get("/accounts/${UUID.randomUUID()}/statement").with { status == 404 && body.code == 'account_not_found' }
    }

    def "the statement of a cross-currency transfer shows each side in its own currency"() {
        given:
        def usd = api.createAccount('USD', '10.00')
        def eur = api.createAccount('EUR')
        api.transfer(newKey(), usd, eur, '10.00')

        expect:
        api.get("/accounts/$usd/statement").body.entries[0].with { amount == [amount: '-10.00', currency: 'USD'] && balanceAfter.amount == '0.00' }
        api.get("/accounts/$eur/statement").body.entries[0].with { amount == [amount: '9.20', currency: 'EUR'] && balanceAfter.amount == '9.20' }
    }

    // ---- misc -----------------------------------------------------------------------------

    def "GET /health reports Postgres and Redis"() {
        when:
        def r = api.get('/health')

        then:
        r.status == 200
        r.body == [status: 'up', postgres: 'up', redis: 'up']
    }

    def "unknown routes return the JSON error shape"() {
        when:
        def r = api.get('/nope')

        then:
        r.status == 404
        r.body.code == 'route_not_found'
        r.header('Content-Type').startsWith('application/json')
    }
}
