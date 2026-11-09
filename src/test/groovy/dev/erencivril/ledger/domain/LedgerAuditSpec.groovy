package dev.erencivril.ledger.domain

import dev.erencivril.ledger.domain.LedgerAudit.AccountRow
import dev.erencivril.ledger.domain.LedgerAudit.EntryRow
import spock.lang.Specification

import static dev.erencivril.ledger.domain.Currency.*

/**
 * LedgerAudit is the oracle the stress tests and the benchmark use, so it is tested on its own:
 * every kind of damage it is supposed to notice, and the clean cases it must leave alone.
 */
class LedgerAuditSpec extends Specification {

    def treasury = new AccountRow(UUID.randomUUID(), AccountKind.SYSTEM, null)
    def alice = UUID.randomUUID()
    def bob = UUID.randomUUID()

    static AccountRow customer(UUID id, String stored) {
        new AccountRow(id, AccountKind.CUSTOMER, new BigDecimal(stored))
    }

    static EntryRow entry(long id, UUID transfer, UUID account, String amount, String balanceAfter = null, Currency currency = USD) {
        new EntryRow(id, transfer, account, currency, new BigDecimal(amount), balanceAfter == null ? null : new BigDecimal(balanceAfter))
    }

    /** Alice is funded with 100.00 by the treasury, then sends 30.00 to Bob. */
    def cleanEntries() {
        def deposit = UUID.randomUUID()
        def payment = UUID.randomUUID()
        [
                entry(1, deposit, treasury.id(), '-100.00'),
                entry(2, deposit, alice, '100.00', '100.00'),
                entry(3, payment, alice, '-30.00', '70.00'),
                entry(4, payment, bob, '30.00', '30.00'),
        ]
    }

    def accounts() { [treasury, customer(alice, '70.00'), customer(bob, '30.00')] }

    def "a clean ledger has no violations"() {
        expect:
        LedgerAudit.violations(accounts(), cleanEntries()).isEmpty()
    }

    def "an empty ledger is clean"() {
        expect:
        LedgerAudit.violations([], []).isEmpty()
        LedgerAudit.violations([customer(alice, '0')], []).isEmpty()
    }

    def "the order the entries are handed over in does not matter, only their ids"() {
        expect:
        LedgerAudit.violations(accounts(), cleanEntries().reverse()).isEmpty()
    }

    def "a cross-currency transfer is clean when each currency sums to zero on its own"() {
        given: 'Alice pays 10.00 USD, Carol receives 9.20 EUR, the two treasuries take the other side'
        def fx = UUID.randomUUID()
        def carol = UUID.randomUUID()
        def eurTreasury = new AccountRow(UUID.randomUUID(), AccountKind.SYSTEM, null)
        def entries = cleanEntries() + [
                entry(5, fx, alice, '-10.00', '60.00'),
                entry(6, fx, treasury.id(), '10.00'),
                entry(7, fx, eurTreasury.id(), '-9.20', null, EUR),
                entry(8, fx, carol, '9.20', '9.20', EUR),
        ]
        def accounts = [treasury, eurTreasury, customer(alice, '60.00'), customer(bob, '30.00'), customer(carol, '9.20')]

        expect:
        LedgerAudit.violations(accounts, entries).isEmpty()
    }

    def "a transfer that does not sum to zero is reported with its transfer id and currency"() {
        given:
        def broken = UUID.randomUUID()
        def entries = cleanEntries() + [
                entry(5, broken, alice, '-5.00', '65.00'),
                entry(6, broken, bob, '4.99', '34.99'),
        ]
        def accounts = [treasury, customer(alice, '65.00'), customer(bob, '34.99')]

        when:
        def problems = LedgerAudit.violations(accounts, entries)

        then:
        problems.size() == 1
        problems[0].contains(broken.toString())
        problems[0].contains('USD')
        problems[0].contains('-0.01')
    }

    def "a transfer that balances in USD but not in EUR is reported for EUR only"() {
        given:
        def transfer = UUID.randomUUID()
        def eurTreasury = UUID.randomUUID()
        def entries = [
                entry(1, transfer, treasury.id(), '-10.00'),
                entry(2, transfer, alice, '10.00', '10.00'),
                entry(3, transfer, eurTreasury, '-9.20', null, EUR),
                entry(4, transfer, bob, '9.00', '9.00', EUR),
        ]
        def accounts = [treasury, new AccountRow(eurTreasury, AccountKind.SYSTEM, null), customer(alice, '10.00'), customer(bob, '9.00')]

        when:
        def problems = LedgerAudit.violations(accounts, entries)

        then:
        problems.size() == 1
        problems[0].contains('EUR')
        !problems[0].contains('USD')
    }

    def "a stored balance that differs from the replayed journal is reported"() {
        when:
        def problems = LedgerAudit.violations([treasury, customer(alice, '70.00'), customer(bob, expected)], cleanEntries())

        then:
        problems.size() == 1
        problems[0].contains(bob.toString())
        problems[0].contains('replays to 30.00')

        where:
        expected << ['30.01', '29.99', '0', '-30.00']
    }

    def "balances are compared by value, not by scale"() {
        expect:
        LedgerAudit.violations([treasury, customer(alice, '70.0000'), customer(bob, '30')], cleanEntries()).isEmpty()
    }

    def "a customer account that is negative at any point of the replay is reported, even if it ends positive"() {
        given: 'Alice is debited 30.00 (entry 1) before she is credited 50.00 (entry 3)'
        def first = UUID.randomUUID()
        def second = UUID.randomUUID()
        def entries = [
                entry(1, first, alice, '-30.00'),
                entry(2, first, treasury.id(), '30.00'),
                entry(3, second, alice, '50.00'),
                entry(4, second, treasury.id(), '-50.00'),
        ]

        when:
        def problems = LedgerAudit.violations([treasury, customer(alice, '20.00')], entries)

        then:
        problems.size() == 1
        problems[0].contains('negative')
        problems[0].contains('entry 1')
    }

    def "an account that is emptied to exactly zero is not negative"() {
        given:
        def deposit = UUID.randomUUID()
        def payment = UUID.randomUUID()
        def entries = [
                entry(1, deposit, treasury.id(), '-5.00'),
                entry(2, deposit, alice, '5.00'),
                entry(3, payment, alice, '-5.00'),
                entry(4, payment, bob, '5.00'),
        ]

        expect:
        LedgerAudit.violations([treasury, customer(alice, '0.00'), customer(bob, '5.00')], entries).isEmpty()
    }

    def "a recorded balance_after that disagrees with the replay is reported"() {
        given:
        def entries = cleanEntries()
        entries[2] = entry(3, entries[2].transferId(), alice, '-30.00', '71.00')

        when:
        def problems = LedgerAudit.violations(accounts(), entries)

        then:
        problems.size() == 1
        problems[0].contains('entry 3')
        problems[0].contains('71.00')
        problems[0].contains('70.00')
    }

    def "treasury accounts may go negative and carry no stored balance"() {
        expect: 'the treasury ends at -100.00 in the clean ledger and nobody minds'
        LedgerAudit.violations(accounts(), cleanEntries()).isEmpty()
    }

    def "an entry for an account that does not exist is reported"() {
        given:
        def ghost = UUID.randomUUID()
        def transfer = UUID.randomUUID()
        def entries = cleanEntries() + [
                entry(5, transfer, ghost, '1.00'),
                entry(6, transfer, treasury.id(), '-1.00'),
        ]

        when:
        def problems = LedgerAudit.violations(accounts(), entries)

        then:
        problems.size() == 1
        problems[0].contains('entry 5')
        problems[0].contains(ghost.toString())
    }

    def "several different problems are all reported"() {
        given:
        def entries = cleanEntries()
        entries[3] = entry(4, entries[3].transferId(), bob, '31.00', '30.00')

        when:
        def problems = LedgerAudit.violations([treasury, customer(alice, '70.00'), customer(bob, '30.00')], entries)

        then: 'the transfer does not balance, balance_after disagrees, and the stored balance disagrees'
        problems.size() == 3
    }
}
