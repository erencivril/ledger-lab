package dev.erencivril.ledger.risk

import dev.erencivril.ledger.domain.risk.*
import spock.lang.Specification

import static dev.erencivril.ledger.domain.risk.RiskTestData.context

class BacktestSpec extends Specification {

    def "the confusion matrix counts errors, not just alerts"() {
        given:
        def engine = new RiskEngine(7, [new FirstPaymentLargeRule(RiskAction.HOLD, 100G)], 0, 0)
        def rows = [
                new SyntheticData.Sample(1, SyntheticData.Pattern.SCAM, context(firstPayment: true, amountUsd: '150.00')),
                new SyntheticData.Sample(2, SyntheticData.Pattern.NORMAL, context(firstPayment: true, amountUsd: '200.00')),
                new SyntheticData.Sample(3, SyntheticData.Pattern.MULE, context(firstPayment: true, amountUsd: '50.00')),
                new SyntheticData.Sample(4, SyntheticData.Pattern.NORMAL, context(firstPayment: true, amountUsd: '10.00')),
        ]

        when:
        def m = Backtest.evaluate(engine, rows, 2)

        then:
        m.tp() == 1
        m.fp() == 1
        m.fn() == 1
        m.tn() == 1
        m.precision() == 0.5d
        m.recall() == 0.5d
        m.precisionAtK() == 0.5d
        m.k() == 2
        m.ruleSetVersion() == 7
    }

    def "precision at k ranks by action then hit count then sample id; k is clamped to sample size"() {
        given:
        def engine = new RiskEngine(1, [new FirstPaymentLargeRule(RiskAction.HOLD, 100G)], 0, 0)
        def rows = [
                new SyntheticData.Sample(1, SyntheticData.Pattern.SCAM, context(firstPayment: true, amountUsd: '150.00')),
                new SyntheticData.Sample(2, SyntheticData.Pattern.NORMAL, context(firstPayment: true, amountUsd: '200.00')),
        ]

        expect:
        Backtest.evaluate(engine, rows.reverse(), 1).precisionAtK() == 1d
        Backtest.evaluate(engine, rows, 100).k() == 2
        Backtest.evaluate(engine, rows, 100).precisionAtK() == 0.5d
    }

    def "undefined metrics are NaN, not invented zeroes"() {
        given:
        def engine = new RiskEngine(1, [], 0, 0)

        expect:
        Double.isNaN(Backtest.evaluate(engine, [], 100).precision())
        Double.isNaN(Backtest.evaluate(engine, [], 100).recall())
        Double.isNaN(Backtest.evaluate(engine, [], 100).precisionAtK())

        when:
        Backtest.evaluate(engine, [], 0)

        then:
        thrown(IllegalArgumentException)
    }

    def "generator is deterministic, contains normal and injected patterns, and does not force every bad payment to trigger"() {
        given:
        def rows = SyntheticData.generate(1000, 42)

        expect:
        rows == SyntheticData.generate(1000, 42)
        rows != SyntheticData.generate(1000, 43)
        rows.size() == 1000
        rows*.id().toSet().size() == 1000
        rows*.pattern().toSet() == SyntheticData.Pattern.values().toList().toSet()
        rows.every { it.context().amountUsd() > 0 }

        when:
        SyntheticData.generate(-1, 42)

        then:
        thrown(IllegalArgumentException)
    }

    def "WARN is not classified as an intervention, STEP_UP is"() {
        given:
        def row = new SyntheticData.Sample(1, SyntheticData.Pattern.SCAM, context(firstPayment: true, amountUsd: '150.00'))

        expect:
        Backtest.evaluate(new RiskEngine(1, [new FirstPaymentLargeRule(RiskAction.WARN, 1G)], 0, 0), [row], 1).fn() == 1
        Backtest.evaluate(new RiskEngine(1, [new FirstPaymentLargeRule(RiskAction.STEP_UP, 1G)], 0, 0), [row], 1).tp() == 1
    }
    def "saved synthetic observations round trip without adding derived money properties"() {
        given:
        def sample = SyntheticData.generate(1, 42)[0]
        def json = RiskCli.jsonMapper()

        when:
        def text = json.writeValueAsString(sample)
        def restored = json.readValue(text, SyntheticData.Sample)

        then:
        restored == sample
        !text.contains('"zero"')
        !text.contains('"positive"')
    }
}
