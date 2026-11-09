package dev.erencivril.ledger.domain.risk

import spock.lang.Specification

import static dev.erencivril.ledger.domain.risk.RiskAction.*
import static dev.erencivril.ledger.domain.risk.RiskTestData.context

class RulesSpec extends Specification {

    // ---- FIRST_PAYMENT_LARGE ------------------------------------------------------------------

    def "FIRST_PAYMENT_LARGE fires only for a first payment at or above the threshold: first=#first amount=#amount"() {
        given:
        def rule = new FirstPaymentLargeRule(HOLD, new BigDecimal('1000'))

        when:
        def hit = rule.evaluate(context(firstPayment: first, amountUsd: amount))

        then:
        hit.isPresent() == fires
        rule.id() == 'FIRST_PAYMENT_LARGE'

        where:
        first | amount    | fires
        true  | '1000.00' | true
        true  | '1000.01' | true
        true  | '999.99'  | false
        false | '5000.00' | false
        true  | '1.00'    | false
    }

    def "FIRST_PAYMENT_LARGE reports the action it was configured with and the amount"() {
        when:
        def hit = new FirstPaymentLargeRule(STEP_UP, new BigDecimal('10')).evaluate(context(firstPayment: true, amountUsd: '250.50')).get()

        then:
        hit.ruleId() == 'FIRST_PAYMENT_LARGE'
        hit.action() == STEP_UP
        hit.detail() == 'first payment to this payee, 250.50 USD'
    }

    // ---- NEW_PAYEE_ACCOUNT --------------------------------------------------------------------

    def "NEW_PAYEE_ACCOUNT fires for a young payee and a big enough amount: age=#hours h amount=#amount"() {
        given:
        def rule = new NewPayeeAccountRule(WARN, java.time.Duration.ofHours(72), new BigDecimal('300'))

        expect:
        rule.evaluate(context(payeeAgeHours: hours, amountUsd: amount)).isPresent() == fires
        rule.id() == 'NEW_PAYEE_ACCOUNT'

        where:
        hours | amount   | fires
        1     | '300.00' | true
        71    | '300.00' | true
        72    | '300.00' | false
        1     | '299.99' | false
        500   | '900.00' | false
    }

    def "NEW_PAYEE_ACCOUNT says how old the account is"() {
        when:
        def hit = new NewPayeeAccountRule(WARN, java.time.Duration.ofHours(72), new BigDecimal('1'))
                .evaluate(context(payeeAgeHours: 2, amountUsd: '10.00')).get()

        then:
        hit.action() == WARN
        hit.detail() == 'payee account is 120 minutes old, amount 10.00 USD'
    }

    // ---- AMOUNT_DEVIATION ---------------------------------------------------------------------

    def "AMOUNT_DEVIATION compares with the sender's average: amount=#amount average=#average history=#history"() {
        given:
        def rule = new AmountDeviationRule(STEP_UP, new BigDecimal('5'), 3)

        expect:
        rule.evaluate(context(amountUsd: amount, averageUsd: average, historyCount: history)).isPresent() == fires
        rule.id() == 'AMOUNT_DEVIATION'

        where:
        amount    | average  | history | fires
        '500.00'  | '100.00' | 3       | true     // exactly 5 times
        '499.99'  | '100.00' | 3       | false
        '900.00'  | '100.00' | 10      | true
        '900.00'  | '100.00' | 2       | false    // not enough history to know what usual is
        '900.00'  | null     | 5       | false    // no average at all
        '900.00'  | '0.00'   | 5       | false    // an average of zero cannot be compared with
    }

    def "AMOUNT_DEVIATION puts the ratio and the average in the detail"() {
        when:
        def hit = new AmountDeviationRule(WARN, new BigDecimal('2'), 1)
                .evaluate(context(amountUsd: '250.00', averageUsd: '100.00', historyCount: 4)).get()

        then:
        hit.action() == WARN
        hit.detail() == "amount is 2.5 times the sender's average of 100.00 USD"
    }
}
