package dev.erencivril.ledger.domain.risk

import spock.lang.Specification

import static dev.erencivril.ledger.domain.risk.RiskAction.*
import static dev.erencivril.ledger.domain.risk.RiskTestData.config
import static dev.erencivril.ledger.domain.risk.RiskTestData.context

class RuleCatalogSpec extends Specification {

    def ruleSet = new RuleSet(4, 'test', 2, 3, [
            config('FIRST_PAYMENT_LARGE', HOLD, [minAmountUsd: '1000']),
            config('NEW_PAYEE_ACCOUNT', WARN, [maxAgeHours: '72', minAmountUsd: '300']),
            config('AMOUNT_DEVIATION', STEP_UP, [factor: '5', minHistory: '3']),
    ])

    def "the engine carries the version and the stacking limits of the rule set"() {
        given:
        def engine = RuleCatalog.build(ruleSet)

        expect:
        engine.ruleSetVersion() == 4

        and: "one WARN stays WARN, but two WARN hits stack to STEP_UP because the set says stack warnings=2"
        engine.assess(context(payeeAgeHours: 1, amountUsd: '400.00')).action() == WARN
        RuleCatalog.build(new RuleSet(1, 'd', 2, 0, [
                config('NEW_PAYEE_ACCOUNT', WARN, [maxAgeHours: '72', minAmountUsd: '1']),
                config('AMOUNT_DEVIATION', WARN, [factor: '2', minHistory: '1'])]))
                .assess(context(payeeAgeHours: 1, amountUsd: '400.00', historyCount: 2, averageUsd: '100.00'))
                .action() == STEP_UP
    }

    def "each rule id builds its rule with its own settings"() {
        given:
        def engine = RuleCatalog.build(ruleSet)

        expect:
        engine.assess(context(firstPayment: true, amountUsd: '1000.00')).reasonCodes() == ['FIRST_PAYMENT_LARGE']
        engine.assess(context(amountUsd: '500.00', historyCount: 3, averageUsd: '100.00')).reasonCodes() == ['AMOUNT_DEVIATION']
        engine.assess(context(payeeAgeHours: 71, amountUsd: '300.00')).reasonCodes() == ['NEW_PAYEE_ACCOUNT']
        engine.assess(context(payeeAgeHours: 72, amountUsd: '300.00')).reasonCodes() == []
    }

    def "rules are evaluated in the order of the rule set"() {
        when:
        def codes = RuleCatalog.build(ruleSet)
                .assess(context(firstPayment: true, amountUsd: '1000.00', payeeAgeHours: 1, historyCount: 3, averageUsd: '100.00'))
                .reasonCodes()

        then:
        codes == ['FIRST_PAYMENT_LARGE', 'NEW_PAYEE_ACCOUNT', 'AMOUNT_DEVIATION']
    }

    def "a disabled rule is not built"() {
        given:
        def set = new RuleSet(1, 'd', 0, 0, [config('FIRST_PAYMENT_LARGE', HOLD, [minAmountUsd: '10'], false)])

        expect:
        RuleCatalog.build(set).assess(context(firstPayment: true, amountUsd: '999.00')).action() == ALLOW
    }

    def "an unknown rule id stops the build and names the id"() {
        when:
        RuleCatalog.build(new RuleSet(1, 'd', 0, 0, [config('NO_SUCH_RULE', WARN)]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('NO_SUCH_RULE')
    }

    def "a disabled unknown rule is ignored"() {
        expect:
        RuleCatalog.build(new RuleSet(1, 'd', 0, 0, [config('NO_SUCH_RULE', WARN, [:], false)])).ruleSetVersion() == 1
    }

    def "a missing parameter stops the build and names it"() {
        when:
        RuleCatalog.build(new RuleSet(1, 'd', 0, 0, [config('FIRST_PAYMENT_LARGE', HOLD)]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('minAmountUsd')
    }

    def "maxAgeHours can be a fraction of an hour"() {
        given:
        def set = new RuleSet(1, 'd', 0, 0, [config('NEW_PAYEE_ACCOUNT', WARN, [maxAgeHours: '0.5', minAmountUsd: '1'])])

        expect:
        RuleCatalog.build(set).assess(context(payeeAgeHours: 0.4, amountUsd: '5.00')).action() == WARN
        RuleCatalog.build(set).assess(context(payeeAgeHours: 0.6, amountUsd: '5.00')).action() == ALLOW
    }
}
