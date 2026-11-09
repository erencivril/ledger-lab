package dev.erencivril.ledger.domain.risk

import spock.lang.Specification

import static dev.erencivril.ledger.domain.risk.RiskAction.*

class RiskActionSpec extends Specification {

    def "the actions are ordered from mildest to strictest"() {
        expect:
        RiskAction.values().toList() == [ALLOW, WARN, STEP_UP, HOLD, BLOCK]
    }

    def "atLeast compares severity: #a atLeast #b is #expected"() {
        expect:
        a.atLeast(b) == expected

        where:
        a       | b       | expected
        ALLOW   | ALLOW   | true
        WARN    | ALLOW   | true
        ALLOW   | WARN    | false
        HOLD    | STEP_UP | true
        STEP_UP | HOLD    | false
        BLOCK   | HOLD    | true
        HOLD    | BLOCK   | false
    }

    def "orStricter returns the stricter of two, whichever side it is on"() {
        expect:
        a.orStricter(b) == expected
        b.orStricter(a) == expected

        where:
        a       | b       | expected
        ALLOW   | WARN    | WARN
        STEP_UP | HOLD    | HOLD
        BLOCK   | ALLOW   | BLOCK
        WARN    | WARN    | WARN
    }
}
