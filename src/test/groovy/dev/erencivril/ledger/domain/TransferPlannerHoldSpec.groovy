package dev.erencivril.ledger.domain

import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant

import static dev.erencivril.ledger.domain.Currency.*

class TransferPlannerHoldSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-01-01T00:00:00Z')

    @Shared Map<Currency, UUID> treasuryIds = Currency.values().collectEntries { [(it): UUID.randomUUID()] }
    @Shared Map<Currency, UUID> holdIds = Currency.values().collectEntries { [(it): UUID.randomUUID()] }
    @Shared FxRateTable rates = new FxRateTable([
            new FxRate(USD, EUR, new BigDecimal('0.92')),
            new FxRate(EUR, USD, new BigDecimal('1.08695652')),
            new FxRate(USD, JPY, new BigDecimal('151.20')),
            new FxRate(JPY, USD, new BigDecimal('0.0001')),
    ])

    def planner = new TransferPlanner(rates, new TreasuryAccounts(treasuryIds), new HoldAccounts(holdIds))

    static Money money(String amount, Currency currency = USD) { new Money(new BigDecimal(amount), currency) }

    static Account account(String balance, Currency currency = USD) {
        new Account(UUID.randomUUID(), 'owner', currency, AccountKind.CUSTOMER, money(balance, currency), NOW)
    }

    def "placing a hold moves the money from the sender to the hold account and leaves the payee alone"() {
        given:
        def from = account('100.00')
        def to = account('5.00')

        when:
        def plan = planner.planHold(from, to, money('30.00'))

        then:
        plan.sourceAmount() == money('30.00')
        plan.destinationAmount() == money('30.00')
        plan.fxRate() == null
        plan.sourceBalanceAfter() == money('70.00')
        plan.journal().lines() == [
                new JournalLine(from.id(), money('-30.00')),
                new JournalLine(holdIds[USD], money('30.00')),
        ]
    }

    def "a hold needs the same checks as a transfer: enough money, a positive amount, two different accounts"() {
        given:
        def from = account('10.00')
        def to = account('0.00')

        when:
        planner.planHold(from, to, money('10.01'))

        then:
        thrown(DomainException.InsufficientFunds)

        when:
        planner.planHold(from, from, money('1.00'))

        then:
        thrown(DomainException)
    }

    def "a cross-currency hold freezes the converted amount now"() {
        given:
        def from = account('100.00')
        def to = account('0', EUR)

        when:
        def plan = planner.planHold(from, to, money('10.00'))

        then:
        plan.sourceAmount() == money('10.00')
        plan.destinationAmount() == money('9.20', EUR)
        plan.fxRate() == new BigDecimal('0.92')
        plan.journal().lines() == [
                new JournalLine(from.id(), money('-10.00')),
                new JournalLine(holdIds[USD], money('10.00')),
        ]
    }

    def "a cross-currency hold fails now if there is no rate, not at release time"() {
        when:
        planner.planHold(account('100.00'), account('0', GBP), money('10.00'))

        then:
        thrown(DomainException.NoFxRate)
    }

    def "a hold that converts to nothing is refused"() {
        when:
        planner.planHold(account('1000', JPY), account('0'), money('1', JPY))

        then:
        thrown(DomainException.InvalidAmount)
    }

    def "releasing a same-currency hold pays the payee from the hold account"() {
        given:
        def to = account('5.00')
        def pending = new Transfer(UUID.randomUUID(), TransferKind.TRANSFER, UUID.randomUUID(), to.id(),
                money('30.00'), money('30.00'), null, NOW, TransferStatus.PENDING)

        when:
        def plan = planner.planRelease(pending, to)

        then:
        plan.journal().lines() == [
                new JournalLine(holdIds[USD], money('-30.00')),
                new JournalLine(to.id(), money('30.00')),
        ]
        plan.paidAccountBalanceAfter() == money('35.00')
    }

    def "releasing a cross-currency hold converts through the treasury with the frozen amounts"() {
        given:
        def to = account('1.00', EUR)
        def pending = new Transfer(UUID.randomUUID(), TransferKind.TRANSFER, UUID.randomUUID(), to.id(),
                money('10.00'), money('9.20', EUR), new BigDecimal('0.92'), NOW, TransferStatus.PENDING)

        when:
        def plan = planner.planRelease(pending, to)

        then:
        plan.journal().lines() == [
                new JournalLine(holdIds[USD], money('-10.00')),
                new JournalLine(treasuryIds[USD], money('10.00')),
                new JournalLine(treasuryIds[EUR], money('-9.20', EUR)),
                new JournalLine(to.id(), money('9.20', EUR)),
        ]
        plan.paidAccountBalanceAfter() == money('10.20', EUR)
    }

    def "rejecting returns the held amount to the sender"() {
        given:
        def from = account('70.00')
        def pending = new Transfer(UUID.randomUUID(), TransferKind.TRANSFER, from.id(), UUID.randomUUID(),
                money('30.00'), money('27.60', EUR), new BigDecimal('0.92'), NOW, TransferStatus.PENDING)

        when:
        def plan = planner.planReject(pending, from)

        then:
        plan.journal().lines() == [
                new JournalLine(holdIds[USD], money('-30.00')),
                new JournalLine(from.id(), money('30.00')),
        ]
        plan.paidAccountBalanceAfter() == money('100.00')
    }

    def "a planner built without hold accounts cannot hold"() {
        when:
        new TransferPlanner(rates, new TreasuryAccounts(treasuryIds)).planHold(account('100.00'), account('0'), money('1.00'))

        then:
        thrown(IllegalStateException)
    }
}
