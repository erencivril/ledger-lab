package dev.erencivril.ledger.domain.risk

import spock.lang.Specification

import static dev.erencivril.ledger.domain.risk.RiskAction.*
import static dev.erencivril.ledger.domain.risk.RiskTestData.context

class RiskEngineSpec extends Specification {

    /** A rule that always fires with the given action, to test the engine without any real rule. */
    static Rule always(String id, RiskAction action) {
        new Rule() {
            String id() { id }
            Optional<RuleHit> evaluate(RiskContext c) { Optional.of(new RuleHit(id, action, id + ' fired')) }
        }
    }

    static Rule never(String id) {
        new Rule() {
            String id() { id }
            Optional<RuleHit> evaluate(RiskContext c) { Optional.empty() }
        }
    }

    def "no rule firing means ALLOW with no reason codes"() {
        when:
        def decision = new RiskEngine(7, [never('A'), never('B')], 0, 0).assess(context())

        then:
        decision.action() == ALLOW
        decision.hits().isEmpty()
        decision.ruleSetVersion() == 7
    }

    def "no rules at all is ALLOW"() {
        expect:
        new RiskEngine(1, [], 0, 0).assess(context()).action() == ALLOW
    }

    def "the strictest hit wins and the reason codes keep the order of the rules"() {
        when:
        def decision = new RiskEngine(1, [always('A', WARN), never('B'), always('C', HOLD), always('D', STEP_UP)], 0, 0)
                .assess(context())

        then:
        decision.action() == HOLD
        decision.reasonCodes() == ['A', 'C', 'D']
    }

    def "one hit gives exactly its own action: #action"() {
        expect:
        new RiskEngine(1, [always('A', action)], 0, 0).assess(context()).action() == action

        where:
        action << [WARN, STEP_UP, HOLD, BLOCK]
    }

    def "stacking: #warnings WARN and #stepUps STEP_UP hits with limits #limitW/#limitS give #expected"() {
        given:
        def rules = (0..<warnings).collect { always("W$it", WARN) } + (0..<stepUps).collect { always("S$it", STEP_UP) }
        def engine = new RiskEngine(1, rules, limitW, limitS)

        expect:
        engine.assess(context()).action() == expected

        where:
        warnings | stepUps | limitW | limitS | expected
        2        | 0       | 2      | 2      | STEP_UP     // two warnings become a step-up
        1        | 0       | 2      | 2      | WARN        // one is not enough
        3        | 0       | 2      | 2      | STEP_UP     // more warnings do not skip to HOLD
        2        | 0       | 0      | 0      | WARN        // stacking switched off
        0        | 2      | 2      | 2      | HOLD        // two step-ups become a hold
        0        | 1      | 2      | 2      | STEP_UP
        0        | 2      | 2      | 0      | STEP_UP     // step-up stacking off
        0        | 2      | 0      | 2      | HOLD        // warning stacking off does not stop step-up stacking
        1        | 1      | 2      | 2      | STEP_UP     // a warning and a step-up do not add up
    }

    def "stacking never lowers a stricter action"() {
        expect:
        new RiskEngine(1, [always('A', BLOCK), always('B', WARN), always('C', WARN)], 2, 2).assess(context()).action() == BLOCK
        new RiskEngine(1, [always('A', HOLD), always('B', WARN), always('C', WARN)], 2, 2).assess(context()).action() == HOLD
    }

    def "a confirmed step-up becomes a warning, but a hold or a block does not"() {
        given:
        def confirmed = context(stepUpConfirmed: true)

        expect:
        new RiskEngine(1, [always('A', STEP_UP)], 0, 0).assess(confirmed).action() == WARN
        new RiskEngine(1, [always('A', STEP_UP)], 0, 0).assess(context()).action() == STEP_UP
        new RiskEngine(1, [always('A', HOLD)], 0, 0).assess(confirmed).action() == HOLD
        new RiskEngine(1, [always('A', BLOCK)], 0, 0).assess(confirmed).action() == BLOCK
        new RiskEngine(1, [always('A', WARN)], 0, 0).assess(confirmed).action() == WARN
    }

    def "a confirmation does not turn a clean payment into a warning"() {
        expect:
        new RiskEngine(1, [never('A')], 0, 0).assess(context(stepUpConfirmed: true)).action() == ALLOW
    }

    def "stacked step-ups still hold after confirmation; stacked warnings become a warning once confirmed"() {
        given:
        def confirmed = context(stepUpConfirmed: true)

        expect:
        new RiskEngine(1, [always('A', STEP_UP), always('B', STEP_UP)], 2, 2).assess(confirmed).action() == HOLD
        new RiskEngine(1, [always('A', WARN), always('B', WARN)], 2, 2).assess(confirmed).action() == WARN
    }

    def "the decision keeps its hits with their details"() {
        when:
        def decision = new RiskEngine(2, [always('A', WARN)], 0, 0).assess(context())

        then:
        decision.hits() == [new RuleHit('A', WARN, 'A fired')]
        decision.ruleSetVersion() == 2
    }

    def "the engine reports its rule set version"() {
        expect:
        new RiskEngine(9, [], 0, 0).ruleSetVersion() == 9
    }
}
