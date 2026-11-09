package dev.erencivril.ledger.domain

import spock.lang.Specification

import java.time.Instant

import static dev.erencivril.ledger.domain.Currency.*

class AccountSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-01-01T00:00:00Z')

    static Money money(String amount, Currency currency = USD) {
        new Money(new BigDecimal(amount), currency)
    }

    static Account account(String balance, Currency currency = USD) {
        new Account(UUID.randomUUID(), 'eren', currency, AccountKind.CUSTOMER, money(balance, currency), NOW)
    }

    def "open creates a customer account with a zero balance in its own currency"() {
        when:
        def id = UUID.randomUUID()
        def a = Account.open(id, '  Eren  ', JPY, NOW)

        then:
        a.id() == id
        a.owner() == 'Eren'
        a.currency() == JPY
        a.kind() == AccountKind.CUSTOMER
        a.balance() == Money.zero(JPY)
        a.createdAt() == NOW
    }

    def "open rejects a bad owner: '#owner'"() {
        when:
        Account.open(UUID.randomUUID(), owner, USD, NOW)

        then:
        def e = thrown(DomainException.InvalidOwner)
        e.code() == 'invalid_owner'

        where:
        owner << [null, '', '   ', 'x' * 101]
    }

    def "open accepts an owner of exactly the maximum length"() {
        expect:
        Account.open(UUID.randomUUID(), 'x' * 100, USD, NOW).owner().length() == 100
    }

    def "credit adds and debit subtracts, leaving the original untouched"() {
        given:
        def a = account('10.00')

        expect:
        a.credit(money('2.50')).balance() == money('12.50')
        a.debit(money('2.50')).balance() == money('7.50')
        a.balance() == money('10.00')
    }

    def "debit may take the whole balance but not one cent more"() {
        given:
        def a = account('10.00')

        expect:
        a.debit(money('10.00')).balance() == money('0.00')

        when:
        a.debit(money('10.01'))

        then:
        def e = thrown(DomainException.InsufficientFunds)
        e.code() == 'insufficient_funds'
        e.message.contains(a.id().toString())
    }

    def "balance must be in the account currency"() {
        when:
        new Account(UUID.randomUUID(), 'eren', USD, AccountKind.CUSTOMER, money('1', EUR), NOW)

        then:
        thrown(IllegalArgumentException)
    }

    def "a null component is rejected"() {
        when:
        new Account(id, owner, USD, kind, Money.zero(USD), NOW)

        then:
        thrown(NullPointerException)

        where:
        id                | owner | kind
        null              | 'a'   | AccountKind.CUSTOMER
        UUID.randomUUID() | null  | AccountKind.CUSTOMER
        UUID.randomUUID() | 'a'   | null
    }

    def "debitWithoutFundsCheck may take the balance below zero and leaves the original untouched"() {
        given:
        def a = account('10.00')

        expect:
        a.debitWithoutFundsCheck(money('4.00')).balance() == money('6.00')
        a.debitWithoutFundsCheck(money('10.01')).balance() == money('-0.01')
        a.balance() == money('10.00')
    }

    def "debitWithoutFundsCheck still refuses a different currency"() {
        when:
        account('10.00').debitWithoutFundsCheck(money('1.00', EUR))

        then:
        thrown(DomainException.CurrencyMismatch)
    }
}
