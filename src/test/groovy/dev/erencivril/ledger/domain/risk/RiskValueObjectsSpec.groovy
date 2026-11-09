package dev.erencivril.ledger.domain.risk

import dev.erencivril.ledger.domain.Currency
import dev.erencivril.ledger.domain.Money
import spock.lang.Specification

import java.time.Duration

import static dev.erencivril.ledger.domain.risk.RiskAction.*
import static dev.erencivril.ledger.domain.risk.RiskTestData.config

class RiskValueObjectsSpec extends Specification {

    def "a rule hit needs all three parts"() {
        when:
        new RuleHit(id, action, detail)

        then:
        thrown(NullPointerException)

        where:
        id   | action | detail
        null | WARN   | 'd'
        'R'  | null   | 'd'
        'R'  | WARN   | null
    }

    def "a decision lists the rule ids of its hits in order and keeps its own copy of the list"() {
        given:
        def hits = [new RuleHit('A', WARN, 'a'), new RuleHit('B', HOLD, 'b')]
        def decision = new RiskDecision(HOLD, hits, 3)

        when:
        hits.clear()

        then:
        decision.reasonCodes() == ['A', 'B']
        decision.ruleSetVersion() == 3
        decision.action() == HOLD
    }

    def "a context rejects missing parts and a negative history"() {
        given:
        def usd = new Money(new BigDecimal('10.00'), Currency.USD)

        when:
        new RiskContext(amount, amountUsd, false, age, count, null, false)

        then:
        thrown(expected)

        where:
        amount                                      | amountUsd          | age              | count | expected
        null                                        | BigDecimal.TEN     | Duration.ZERO    | 0     | NullPointerException
        new Money(BigDecimal.TEN, Currency.USD)     | null               | Duration.ZERO    | 0     | NullPointerException
        new Money(BigDecimal.TEN, Currency.USD)     | BigDecimal.TEN     | null             | 0     | NullPointerException
        new Money(BigDecimal.TEN, Currency.USD)     | BigDecimal.TEN     | Duration.ZERO    | -1    | IllegalArgumentException
    }

    def "a rule config rejects ALLOW, because a rule that fires cannot ask for nothing"() {
        when:
        config('X', ALLOW)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('X')
    }

    def "a rule config returns a parameter by name and names the rule and parameter when one is missing"() {
        given:
        def rule = config('X', WARN, [limit: '5'])

        expect:
        rule.param('limit') == new BigDecimal('5')

        when:
        rule.param('other')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('X')
        e.message.contains('other')
    }

    def "a rule config needs an id and an action"() {
        when:
        new RuleConfig(id, action, true, [:])

        then:
        thrown(NullPointerException)

        where:
        id  | action
        null | WARN
        'X'  | null
    }

    def "a rule set must have a positive version, no negative stacking and no repeated rule"() {
        when:
        new RuleSet(version, 'd', warnings, stepUps, rules)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(text)

        where:
        version | warnings | stepUps | rules                                       | text
        0       | 0        | 0       | []                                          | 'version'
        1       | -1       | 0       | []                                          | 'stacking'
        1       | 0        | -1      | []                                          | 'stacking'
        1       | 0        | 0       | [config('A', WARN), config('A', HOLD)]      | 'twice'
    }

    def "a rule set needs a description and accepts distinct rules"() {
        when:
        new RuleSet(1, null, 0, 0, [])

        then:
        thrown(NullPointerException)

        expect:
        new RuleSet(1, 'd', 2, 3, [config('A', WARN), config('B', HOLD)]).rules()*.id() == ['A', 'B']
    }
}
