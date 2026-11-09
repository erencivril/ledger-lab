package dev.erencivril.ledger.domain

import spock.lang.Specification

import static dev.erencivril.ledger.domain.Currency.*

class MoneySpec extends Specification {

    static Money money(String amount, Currency currency) {
        new Money(new BigDecimal(amount), currency)
    }

    def "an exact amount is stored at the currency scale: #input #currency -> #stored"() {
        expect:
        money(input, currency).amount().toPlainString() == stored

        where:
        input    | currency | stored
        '10'     | USD      | '10.00'
        '10.5'   | USD      | '10.50'
        '10.50'  | USD      | '10.50'
        '1E+2'   | USD      | '100.00'
        '10.000' | USD      | '10.00'
        '0'      | USD      | '0.00'
        '-3.1'   | EUR      | '-3.10'
        '500'    | JPY      | '500'
        '500.0'  | JPY      | '500'
        '1'      | KWD      | '1.000'
        '1.25'   | KWD      | '1.250'
    }

    def "an amount with too many decimals is rejected, not rounded: #input #currency"() {
        when:
        money(input, currency)

        then:
        def e = thrown(DomainException.InvalidAmount)
        e.code() == 'invalid_amount'
        e.message.contains("allows at most ${currency.minorUnits()} decimal places")

        where:
        input    | currency
        '1.005'  | USD
        '0.001'  | EUR
        '10.999' | GBP
        '1.5'    | JPY
        '0.1'    | JPY
        '1.0005' | KWD
    }

    def "rounded uses HALF_EVEN (banker's rounding): #input #currency -> #expected"() {
        expect:
        Money.rounded(new BigDecimal(input), currency).amount().toPlainString() == expected

        where:
        input     | currency | expected
        '1.005'   | USD      | '1.00'
        '1.015'   | USD      | '1.02'
        '1.025'   | USD      | '1.02'
        '1.035'   | USD      | '1.04'
        '1.0051'  | USD      | '1.01'
        '1.0049'  | USD      | '1.00'
        '-1.005'  | USD      | '-1.00'
        '-1.015'  | USD      | '-1.02'
        '0.5'     | JPY      | '0'
        '1.5'     | JPY      | '2'
        '2.5'     | JPY      | '2'
        '3.5'     | JPY      | '4'
        '-2.5'    | JPY      | '-2'
        '0.1235'  | KWD      | '0.124'
        '0.1245'  | KWD      | '0.124'
        '10'      | USD      | '10.00'
    }

    def "zero is zero at every scale"() {
        expect:
        Money.zero(currency).amount().toPlainString() == text
        Money.zero(currency).isZero()
        !Money.zero(currency).isPositive()
        !Money.zero(currency).isNegative()

        where:
        currency | text
        USD      | '0.00'
        JPY      | '0'
        KWD      | '0.000'
    }

    def "sign checks: #amount"() {
        given:
        def m = money(amount, USD)

        expect:
        m.isPositive() == positive
        m.isNegative() == negative
        m.isZero() == zero

        where:
        amount  | positive | negative | zero
        '0.01'  | true     | false    | false
        '-0.01' | false    | true     | false
        '0.00'  | false    | false    | true
    }

    def "plus and minus: #a #op #b = #result"() {
        expect:
        (op == '+' ? money(a, USD).plus(money(b, USD)) : money(a, USD).minus(money(b, USD))) == money(result, USD)

        where:
        a        | op  | b      | result
        '10.00'  | '+' | '0.01' | '10.01'
        '0.10'   | '+' | '0.20' | '0.30'
        '10.00'  | '-' | '0.01' | '9.99'
        '1.00'   | '-' | '2.50' | '-1.50'
        '5.00'   | '-' | '5.00' | '0.00'
    }

    def "negate flips the sign and keeps the currency"() {
        expect:
        money('5.25', EUR).negate() == money('-5.25', EUR)
        money('-5.25', EUR).negate() == money('5.25', EUR)
        money('0', EUR).negate() == money('0', EUR)
    }

    def "adding or subtracting different currencies fails"() {
        when:
        operation.call()

        then:
        def e = thrown(DomainException.CurrencyMismatch)
        e.code() == 'currency_mismatch'

        where:
        operation << [
                { money('1', USD).plus(money('1', EUR)) },
                { money('1', USD).minus(money('1', EUR)) },
                { money('1', USD).compareTo(money('1', EUR)) },
        ]
    }

    def "compareTo orders by amount"() {
        expect:
        money(a, USD) <=> money(b, USD) == order

        where:
        a      | b      | order
        '1.00' | '2.00' | -1
        '2.00' | '1.00' | 1
        '1.00' | '1.00' | 0
        '-1.00'| '0.00' | -1
    }

    def "equality ignores how the amount was written"() {
        expect:
        money('10', USD) == money('10.00', USD)
        money('10', USD).hashCode() == money('10.0', USD).hashCode()
        !money('10', USD).equals(money('10', EUR))
    }

    def "toString prints plain digits and the currency"() {
        expect:
        money('1E+3', USD).toString() == '1000.00 USD'
        money('2.5', KWD).toString() == '2.500 KWD'
    }

    def "null arguments are rejected"() {
        when:
        new Money(amount, currency)

        then:
        thrown(NullPointerException)

        where:
        amount           | currency
        null             | USD
        BigDecimal.ONE   | null
    }

    def "sums of many small amounts do not drift the way doubles do"() {
        given:
        def total = (1..10).inject(Money.zero(USD)) { acc, i -> acc.plus(money('0.10', USD)) }

        expect:
        total == money('1.00', USD)
    }
}
