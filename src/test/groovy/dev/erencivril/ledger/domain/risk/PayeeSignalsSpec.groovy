package dev.erencivril.ledger.domain.risk

import spock.lang.Specification

import static dev.erencivril.ledger.domain.risk.RiskAction.*
import static dev.erencivril.ledger.domain.risk.RiskTestData.context

class PayeeSignalsSpec extends Specification {

    def "name normalisation handles Unicode, accents, case, punctuation and whitespace"() {
        expect:
        NameMatcher.normalise(input) == expected

        where:
        input                         | expected
        '  Élodie   MARTIN '           | 'elodie martin'
        'E\u0301lodie Martin'          | 'elodie martin'
        'Ａｌｉｃｅ Smith'             | 'alice smith'
        'İpek Çelik'                  | 'ipek celik'
        "Anne-Marie O'Neil"           | 'anne marie o neil'
        '王 小明'                      | '王 小明'
    }

    def "names are exact, close or different with a reproducible score"() {
        expect:
        NameMatcher.check('Élodie Martin', 'Elodie Martin').grade() == NameMatcher.Grade.MATCH
        NameMatcher.check('Alice Smith', 'Alic Smith').grade() == NameMatcher.Grade.CLOSE
        NameMatcher.check('Alice Smith', 'Alic Smith').similarity() > 0.8d
        NameMatcher.check('Alice Smith', 'Bob Jones').grade() == NameMatcher.Grade.NO_MATCH
        NameMatcher.check('Alice Smith', 'Alice Smith').similarity() == 1d
        NameMatcher.check('', '').grade() == NameMatcher.Grade.NO_MATCH
        NameMatcher.check(null, 'Alice').grade() == NameMatcher.Grade.NOT_PROVIDED
        NameMatcher.check('Alice', '').grade() == NameMatcher.Grade.NO_MATCH
        NameMatcher.check('abcde', 'abxde').grade() == NameMatcher.Grade.CLOSE // boundary 0.8
        NameMatcher.check('abcde', 'axxye').grade() == NameMatcher.Grade.NO_MATCH
    }

    def "mock sanctions use only invented names, normalise but do not fuzzy-match"() {
        expect:
        MockSanctions.contains('  EXAMPLE Restricted Person ')
        MockSanctions.contains('Synthetic Blocked Entity')
        !MockSanctions.contains('Example Restricte Person')
        !MockSanctions.contains('Alice Smith')
    }

    def "payee signal rules report reasons and respect configured actions"() {
        given:
        def c = withSignals(NameMatcher.check('Alice', 'Bob'), true, VelocitySnapshot.empty())

        expect:
        new NameMismatchRule(STEP_UP).evaluate(c).get().ruleId() == 'NAME_NO_MATCH'
        new SanctionsRule(BLOCK).evaluate(c).get().action() == BLOCK
        new NameMismatchRule(STEP_UP).evaluate(context()).isEmpty()
        new SanctionsRule(BLOCK).evaluate(context()).isEmpty()
    }

    def "velocity count and amount thresholds are inclusive in both windows"() {
        given:
        def c = withSignals(NameMatcher.check(null, 'x'), false,
                new VelocitySnapshot(true, 3, 100G, 10, 1000G))

        expect:
        new VelocityRule(HOLD, 1, 3, 999G).evaluate(c).isPresent()
        new VelocityRule(HOLD, 1, 4, 100G).evaluate(c).isPresent()
        new VelocityRule(HOLD, 1, 4, 101G).evaluate(c).isEmpty()
        new VelocityRule(STEP_UP, 24, 10, 9999G).evaluate(c).isPresent()
        new VelocityRule(STEP_UP, 24, 11, 1000G).evaluate(c).isPresent()
        new VelocityRule(STEP_UP, 24, 11, 1001G).evaluate(c).isEmpty()
        new VelocityRule(HOLD, 1, 3, 999G).id() == 'VELOCITY_1H'
        new VelocityRule(HOLD, 24, 10, 999G).id() == 'VELOCITY_24H'
    }

    def "Redis unavailable is always HOLD even with an empty ruleset and confirmation"() {
        given:
        def c = withSignals(NameMatcher.check(null, 'x'), false, VelocitySnapshot.unavailable())

        when:
        def d = new RiskEngine(1, [], 0, 0).assess(c)

        then:
        d.action() == HOLD
        d.reasonCodes() == ['VELOCITY_UNAVAILABLE']
        new VelocityRule(HOLD, 1, 3, 999G).evaluate(c).isEmpty()
    }

    static RiskContext withSignals(NameMatcher.Result name, boolean sanctioned, VelocitySnapshot velocity) {
        def c = context()
        new RiskContext(c.amount(), c.amountUsd(), c.firstPaymentToPayee(), c.payeeAccountAge(),
                c.senderHistoryCount(), c.senderAverageUsd(), true, name, sanctioned, velocity)
    }
}
