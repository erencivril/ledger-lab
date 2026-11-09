package dev.erencivril.ledger.domain

import spock.lang.Specification

import static dev.erencivril.ledger.domain.Currency.*

class FxRateTableSpec extends Specification {

    def usdEur = new FxRate(USD, EUR, new BigDecimal('0.92'))
    def eurUsd = new FxRate(EUR, USD, new BigDecimal('1.08'))

    def "finds a rate by ordered pair"() {
        given:
        def table = new FxRateTable([usdEur, eurUsd])

        expect:
        table.find(USD, EUR).get() == usdEur
        table.find(EUR, USD).get() == eurUsd
        table.size() == 2
    }

    def "returns empty for an unknown pair and does not fall back to the inverse"() {
        given:
        def table = new FxRateTable([usdEur])

        expect:
        table.find(EUR, USD).isEmpty()
        table.find(USD, GBP).isEmpty()
    }

    def "a duplicate pair is rejected"() {
        when:
        new FxRateTable([usdEur, new FxRate(USD, EUR, new BigDecimal('0.93'))])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('USD')
    }

    def "toUsd leaves USD alone, converts with the table and rounds to cents"() {
        given:
        def table = new FxRateTable([new FxRate(EUR, USD, new BigDecimal('1.08695652'))])

        expect:
        table.toUsd(new Money(new BigDecimal('12.34'), USD)) == new Money(new BigDecimal('12.34'), USD)
        table.toUsd(new Money(new BigDecimal('100.00'), EUR)) == new Money(new BigDecimal('108.70'), USD)
    }

    def "toUsd fails with NoFxRate when the currency has no rate to USD"() {
        when:
        new FxRateTable([]).toUsd(new Money(new BigDecimal('1.00'), GBP))

        then:
        thrown(DomainException.NoFxRate)
    }
}
