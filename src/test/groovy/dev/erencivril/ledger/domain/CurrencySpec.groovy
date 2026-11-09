package dev.erencivril.ledger.domain

import spock.lang.Specification

class CurrencySpec extends Specification {

    def "#currency has #digits minor units"() {
        expect:
        currency.minorUnits() == digits

        where:
        currency     | digits
        Currency.USD | 2
        Currency.EUR | 2
        Currency.GBP | 2
        Currency.TRY | 2
        Currency.JPY | 0
        Currency.KWD | 3
    }

    def "parse accepts the exact ISO code '#code'"() {
        expect:
        Currency.parse(code) == expected

        where:
        code  | expected
        'USD' | Currency.USD
        'JPY' | Currency.JPY
        'KWD' | Currency.KWD
    }

    def "parse rejects '#code'"() {
        when:
        Currency.parse(code)

        then:
        def e = thrown(DomainException.UnsupportedCurrency)
        e.code() == 'unsupported_currency'
        e.message == "Unsupported currency: ${code}"

        where:
        code << ['usd', 'Usd', 'XXX', '', ' USD', null]
    }
}
