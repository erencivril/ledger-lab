package dev.erencivril.ledger.domain

import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant

import static dev.erencivril.ledger.domain.Currency.*

class TransferPlannerSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-01-01T00:00:00Z')

    @Shared Map<Currency, UUID> treasuryIds = Currency.values().collectEntries { [(it): UUID.randomUUID()] }
    @Shared TreasuryAccounts treasury = new TreasuryAccounts(treasuryIds)
    @Shared FxRateTable rates = new FxRateTable([
            new FxRate(USD, EUR, new BigDecimal('0.92')),
            new FxRate(EUR, USD, new BigDecimal('1.08695652')),
            new FxRate(USD, JPY, new BigDecimal('151.20')),
            new FxRate(JPY, USD, new BigDecimal('0.00661376')),
            new FxRate(USD, KWD, new BigDecimal('0.307')),
            new FxRate(KWD, USD, new BigDecimal('3.25732899')),
    ])

    def planner = new TransferPlanner(rates, treasury)

    static Money money(String amount, Currency currency = USD) {
        new Money(new BigDecimal(amount), currency)
    }

    static Account account(String balance, Currency currency = USD, AccountKind kind = AccountKind.CUSTOMER) {
        new Account(UUID.randomUUID(), 'owner', currency, kind, money(balance, currency), NOW)
    }

    def "same-currency transfer moves the amount and posts two balanced lines"() {
        given:
        def from = account('100.00')
        def to = account('5.00')

        when:
        def plan = planner.plan(from, to, money('30.25'))

        then:
        plan.sourceAmount() == money('30.25')
        plan.destinationAmount() == money('30.25')
        plan.fxRate() == null
        plan.sourceBalanceAfter() == money('69.75')
        plan.destinationBalanceAfter() == money('35.25')
        plan.journal().lines() == [
                new JournalLine(from.id(), money('-30.25')),
                new JournalLine(to.id(), money('30.25')),
        ]
    }

    def "cross-currency transfer converts, records the rate and balances each currency through the treasury"() {
        given:
        def from = account('100.00', USD)
        def to = account('0.00', EUR)

        when:
        def plan = planner.plan(from, to, money('50.00'))

        then:
        plan.sourceAmount() == money('50.00', USD)
        plan.destinationAmount() == money('46.00', EUR)
        plan.fxRate() == new BigDecimal('0.92')
        plan.sourceBalanceAfter() == money('50.00', USD)
        plan.destinationBalanceAfter() == money('46.00', EUR)
        plan.journal().lines() == [
                new JournalLine(from.id(), money('-50.00', USD)),
                new JournalLine(treasuryIds[USD], money('50.00', USD)),
                new JournalLine(treasuryIds[EUR], money('-46.00', EUR)),
                new JournalLine(to.id(), money('46.00', EUR)),
        ]
    }

    def "conversion respects the target scale: #amount #source -> #expected #target"() {
        given:
        def from = account('1000000.00', source)
        def to = account('0', target)

        when:
        def plan = planner.plan(from, to, money(amount, source))

        then:
        plan.destinationAmount() == money(expected, target)

        where:
        source | target | amount     | expected
        USD    | JPY    | '10.00'    | '1512'
        USD    | KWD    | '10.00'    | '3.070'
        JPY    | USD    | '1000'     | '6.61'
        KWD    | USD    | '2.000'    | '6.51'
        USD    | EUR    | '0.50'     | '0.46'
    }

    def "every direction yields a balanced journal: #amount #source -> #target"() {
        given:
        def from = account('1000000', source)
        def to = account('0', target)

        when:
        def plan = planner.plan(from, to, money(amount, source))

        then:
        plan.journal().lines().size() == (source == target ? 2 : 4)
        plan.sourceBalanceAfter() == from.balance().minus(money(amount, source))
        plan.destinationBalanceAfter() == plan.destinationAmount()

        where:
        source | target | amount
        USD    | USD    | '10.00'
        JPY    | JPY    | '500'
        KWD    | KWD    | '1.234'
        USD    | EUR    | '0.01'
        USD    | EUR    | '12345.67'
        EUR    | USD    | '0.99'
        EUR    | USD    | '999999.99'
        USD    | JPY    | '1.00'
        JPY    | USD    | '1'
        JPY    | USD    | '999999'
        USD    | KWD    | '0.01'
        KWD    | USD    | '0.010'
        KWD    | USD    | '123456.789'
    }

    def "an account that cannot be paid from is rejected: balance #balance, amount #amount"() {
        when:
        planner.plan(account(balance), account('0.00'), money(amount))

        then:
        def e = thrown(DomainException.InsufficientFunds)
        e.code() == 'insufficient_funds'

        where:
        balance | amount
        '10.00' | '10.01'
        '0.00'  | '0.01'
        '0.99'  | '1.00'
    }

    def "the whole balance can be transferred"() {
        when:
        def plan = planner.plan(account('10.00'), account('0.00'), money('10.00'))

        then:
        plan.sourceBalanceAfter() == money('0.00')
        plan.destinationBalanceAfter() == money('10.00')
    }

    def "a transfer to the same account is rejected before anything else"() {
        given:
        def a = account('10.00')

        when:
        planner.plan(a, a, money(amount))

        then:
        def e = thrown(DomainException.SameAccount)
        e.code() == 'same_account'

        where:
        amount << ['1.00', '0.00', '-1.00', '99999.00']
    }

    def "an amount must be greater than zero: #amount"() {
        when:
        planner.plan(account('10.00'), account('0.00'), money(amount))

        then:
        def e = thrown(DomainException.InvalidAmount)
        e.code() == 'invalid_amount'
        e.message.contains('greater than zero')

        where:
        amount << ['0.00', '-0.01', '-100.00']
    }

    def "amounts above the maximum are rejected, the maximum itself is allowed"() {
        given:
        def rich = account('2000000000000.00')

        when:
        planner.plan(rich, account('0.00'), money('1000000000000.00'))

        then:
        noExceptionThrown()

        when:
        planner.plan(rich, account('0.00'), money('1000000000000.01'))

        then:
        def e = thrown(DomainException.InvalidAmount)
        e.message.contains('must not exceed 1000000000000')
    }

    def "the amount must be in the source currency"() {
        when:
        planner.plan(account('10.00', USD), account('0.00', EUR), money('1.00', EUR))

        then:
        def e = thrown(DomainException.CurrencyMismatch)
        e.message.contains('EUR')
        e.message.contains('USD')
    }

    def "a currency pair without a rate is rejected"() {
        when:
        planner.plan(account('10.00', USD), account('0.00', GBP), money('1.00', USD))

        then:
        def e = thrown(DomainException.NoFxRate)
        e.code() == 'no_fx_rate'
        e.message.contains('USD')
        e.message.contains('GBP')
    }

    def "an amount that converts to zero is rejected, not posted as a zero line"() {
        given:
        def tiny = new TransferPlanner(
                new FxRateTable([new FxRate(USD, JPY, new BigDecimal('0.001'))]), treasury)

        when:
        tiny.plan(account('10.00', USD), account('0', JPY), money('1.00', USD))

        then:
        def e = thrown(DomainException.InvalidAmount)
        e.message.contains('converts to zero')
    }

    def "only customer accounts can take part in a transfer"() {
        when:
        planner.plan(account('10.00'), account('0.00', USD, AccountKind.SYSTEM), money('1.00'))

        then:
        thrown(IllegalArgumentException)

        when:
        planner.plan(account('10.00', USD, AccountKind.SYSTEM), account('0.00'), money('1.00'))

        then:
        thrown(IllegalArgumentException)
    }

    def "the plan does not modify the accounts it was given"() {
        given:
        def from = account('10.00')
        def to = account('0.00')

        when:
        planner.plan(from, to, money('4.00'))

        then:
        from.balance() == money('10.00')
        to.balance() == money('0.00')
    }

    def "an opening deposit is paid by the treasury"() {
        given:
        def id = UUID.randomUUID()

        when:
        def journal = planner.openingDeposit(id, money('250.00', EUR))

        then:
        journal.lines() == [
                new JournalLine(treasuryIds[EUR], money('-250.00', EUR)),
                new JournalLine(id, money('250.00', EUR)),
        ]
    }

    def "an opening deposit must be positive and within the maximum: #amount"() {
        when:
        planner.openingDeposit(UUID.randomUUID(), money(amount))

        then:
        thrown(DomainException.InvalidAmount)

        where:
        amount << ['0.00', '-5.00', '1000000000000.01']
    }
}
