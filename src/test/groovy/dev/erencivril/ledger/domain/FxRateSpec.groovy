package dev.erencivril.ledger.domain

import spock.lang.Specification

import static dev.erencivril.ledger.domain.Currency.*

class FxRateSpec extends Specification {

    static Money money(String amount, Currency currency) {
        new Money(new BigDecimal(amount), currency)
    }

    def "converts #amount #from at #rate to #expected #to"() {
        given:
        def fx = new FxRate(from, to, new BigDecimal(rate))

        expect:
        fx.convert(money(amount, from)) == money(expected, to)

        where:
        from | to  | rate         | amount    | expected
        USD  | EUR | '0.92'       | '100.00'  | '92.00'
        USD  | EUR | '0.92'       | '0.01'    | '0.01'
        USD  | EUR | '0.92'       | '0.05'    | '0.05'
        USD  | EUR | '0.92'       | '0.50'    | '0.46'
        EUR  | USD | '1.08695652' | '100.00'  | '108.70'
        USD  | JPY | '151.20'     | '10.00'   | '1512'
        USD  | JPY | '151.20'     | '0.01'    | '2'
        JPY  | USD | '0.00661376' | '1000'    | '6.61'
        USD  | KWD | '0.307'      | '10.00'   | '3.070'
        KWD  | USD | '3.2573'     | '1.000'   | '3.26'
        USD  | EUR | '0.925'      | '1.00'    | '0.92'
        USD  | EUR | '0.935'      | '1.00'    | '0.94'
        USD  | EUR | '0.925'      | '-1.00'   | '-0.92'
    }

    def "a half-cent result rounds to the even cent: #amount at #rate -> #expected"() {
        given:
        def fx = new FxRate(USD, EUR, new BigDecimal(rate))

        expect:
        fx.convert(money(amount, USD)) == money(expected, EUR)

        where:
        amount  | rate  | expected
        '0.25'  | '0.1' | '0.02'
        '0.35'  | '0.1' | '0.04'
        '0.45'  | '0.1' | '0.04'
        '0.55'  | '0.1' | '0.06'
    }

    def "convert rejects money in the wrong currency"() {
        when:
        new FxRate(USD, EUR, new BigDecimal('0.92')).convert(money('1', GBP))

        then:
        def e = thrown(DomainException.CurrencyMismatch)
        e.message.contains('USD')
        e.message.contains('GBP')
    }

    def "an FX rate needs two different currencies and a positive rate"() {
        when:
        new FxRate(from, to, rate)

        then:
        thrown(IllegalArgumentException)

        where:
        from | to  | rate
        USD  | USD | BigDecimal.ONE
        USD  | EUR | BigDecimal.ZERO
        USD  | EUR | new BigDecimal('-0.5')
    }

    def "null parts of an FX rate are rejected"() {
        when:
        new FxRate(from, to, rate)

        then:
        thrown(NullPointerException)

        where:
        from | to  | rate
        null | EUR | BigDecimal.ONE
        USD  | null| BigDecimal.ONE
        USD  | EUR | null
    }
}
