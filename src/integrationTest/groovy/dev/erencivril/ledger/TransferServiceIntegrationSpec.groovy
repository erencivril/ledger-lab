package dev.erencivril.ledger

import dev.erencivril.ledger.application.ApplicationException
import dev.erencivril.ledger.application.TransferService
import dev.erencivril.ledger.domain.Currency
import dev.erencivril.ledger.domain.DomainException
import dev.erencivril.ledger.domain.TransferKind
import dev.erencivril.ledger.support.TestLedger
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

import static dev.erencivril.ledger.domain.Currency.*

class TransferServiceIntegrationSpec extends Specification {

    @Shared TestLedger ledger

    def setupSpec() { ledger = new TestLedger() }

    def cleanupSpec() {
        ledger.assertLedgerConsistent()
        ledger.close()
    }

    def "opening an account with a balance is funded by the treasury through a journal"() {
        when:
        def account = ledger.openAccount(USD, '125.50')

        then:
        account.balance().amount() == 125.50G
        ledger.balanceOf(account.id()) == 125.50G
        def entries = ledger.sql.rows('select * from journal_entries where account_id = ?', [account.id()])
        entries.size() == 1
        entries[0].amount == 125.50G
        entries[0].balance_after == 125.50G
        def deposit = ledger.sql.firstRow('select * from transfers where destination_account_id = ?', [account.id()])
        deposit.kind == TransferKind.OPENING_DEPOSIT.name()
    }

    def "a transfer moves money, writes a balanced journal and returns the stored transfer"() {
        given:
        def from = ledger.openAccount(USD, '100.00')
        def to = ledger.openAccount(USD, '5.00')

        when:
        def result = ledger.transfers.transfer(ledger.command('k-' + UUID.randomUUID(), from, to, '30.25'))

        then:
        !result.replayed()
        result.transfer().sourceAmount().amount() == 30.25G
        result.transfer().fxRate() == null
        ledger.balanceOf(from.id()) == 69.75G
        ledger.balanceOf(to.id()) == 35.25G
        def entries = ledger.sql.rows('select account_id, amount, balance_after from journal_entries where transfer_id = ? order by id', [result.transfer().id()])
        entries*.amount == [-30.25G, 30.25G]
        entries*.balance_after == [69.75G, 35.25G]
        ledger.transfers.get(result.transfer().id()) == result.transfer()
    }

    def "a cross-currency transfer records the rate used and balances each currency through the treasury"() {
        given:
        def usd = ledger.openAccount(USD, '100.00')
        def eur = ledger.openAccount(EUR, '0')

        when:
        def result = ledger.transfers.transfer(ledger.command('k-' + UUID.randomUUID(), usd, eur, '50.00'))

        then:
        result.transfer().destinationAmount().amount() == 46.00G
        result.transfer().destinationAmount().currency() == EUR
        result.transfer().fxRate() == 0.92000000G
        ledger.sql.firstRow('select fx_rate from transfers where id = ?', [result.transfer().id()]).fx_rate == 0.92000000G
        def lines = ledger.sql.rows('select currency, amount, balance_after from journal_entries where transfer_id = ? order by id', [result.transfer().id()])
        lines.size() == 4
        lines.findAll { it.currency == 'USD' }*.amount.sum() == 0G
        lines.findAll { it.currency == 'EUR' }*.amount.sum() == 0G
        lines*.balance_after == [50.00G, null, null, 46.00G]
    }

    def "a rejected transfer changes nothing and does not consume the idempotency key"() {
        given:
        def from = ledger.openAccount(USD, '10.00')
        def to = ledger.openAccount(USD, '0')
        def key = 'k-' + UUID.randomUUID()

        when:
        ledger.transfers.transfer(ledger.command(key, from, to, '10.01'))

        then:
        thrown(DomainException.InsufficientFunds)
        ledger.balanceOf(from.id()) == 10.00G
        ledger.balanceOf(to.id()) == 0G
        ledger.sql.firstRow('select count(*) c from idempotency_keys where idempotency_key = ?', [key]).c == 0
        ledger.sql.firstRow('select count(*) c from journal_entries where account_id = ?', [from.id()]).c == 1 // the opening deposit only

        when: 'the same key is used again with a smaller amount after the failure'
        def result = ledger.transfers.transfer(ledger.command(key, from, to, '4.00'))

        then:
        !result.replayed()
        ledger.balanceOf(from.id()) == 6.00G
    }

    def "an unknown account is a not-found error and rolls back the key"() {
        given:
        def from = ledger.openAccount(USD, '10.00')
        def ghost = [id: { UUID.randomUUID() }]
        def key = 'k-' + UUID.randomUUID()

        when:
        ledger.transfers.transfer(ledger.command(key, from, ghost, '1.00'))

        then:
        def e = thrown(ApplicationException.AccountNotFound)
        e.code() == 'account_not_found'
        ledger.sql.firstRow('select count(*) c from idempotency_keys where idempotency_key = ?', [key]).c == 0
    }

    def "a treasury account cannot be used as source or destination"() {
        given:
        def customer = ledger.openAccount(USD, '10.00')
        def treasuryId = ledger.sql.firstRow("select id from accounts where kind = 'SYSTEM' and currency = 'USD'").id
        def treasury = [id: { treasuryId }]

        when:
        ledger.transfers.transfer(ledger.command('k-' + UUID.randomUUID(), customer, treasury, '1.00'))

        then:
        thrown(ApplicationException.AccountNotFound)

        when:
        ledger.transfers.transfer(ledger.command('k-' + UUID.randomUUID(), treasury, customer, '1.00'))

        then:
        thrown(ApplicationException.AccountNotFound)
    }

    def "retrying with the same key and the same body returns the original transfer and moves money once"() {
        given:
        def from = ledger.openAccount(USD, '100.00')
        def to = ledger.openAccount(USD, '0')
        def key = 'k-' + UUID.randomUUID()

        when:
        def first = ledger.transfers.transfer(ledger.command(key, from, to, '25.00'))
        def second = ledger.transfers.transfer(ledger.command(key, from, to, '25.00'))
        def third = ledger.transfers.transfer(ledger.command(key, from, to, '25'))   // same amount, written differently

        then:
        !first.replayed()
        second.replayed()
        third.replayed()
        second.transfer() == first.transfer()
        third.transfer() == first.transfer()
        ledger.balanceOf(from.id()) == 75.00G
        ledger.sql.firstRow('select count(*) c from transfers where source_account_id = ?', [from.id()]).c == 1
    }

    def "the same key with a different body is a conflict, whichever field differs"() {
        given:
        def from = ledger.openAccount(USD, '100.00')
        def to = ledger.openAccount(USD, '0')
        def other = ledger.openAccount(USD, '0')
        def key = 'k-' + UUID.randomUUID()
        ledger.transfers.transfer(ledger.command(key, from, to, '25.00'))

        when:
        ledger.transfers.transfer(changed.call(key, from, to, other))

        then:
        def e = thrown(ApplicationException.IdempotencyKeyReused)
        e.code() == 'idempotency_key_reused'
        ledger.balanceOf(from.id()) == 75.00G

        where:
        changed << [
                { k, f, t, o -> ledger.command(k, f, t, '25.01') },
                { k, f, t, o -> ledger.command(k, f, o, '25.00') },
                { k, f, t, o -> ledger.command(k, t, f, '25.00') },
        ]
    }

    def "twenty parallel requests with the same key create exactly one transfer"() {
        given:
        def from = ledger.openAccount(USD, '1000.00')
        def to = ledger.openAccount(USD, '0')
        def key = 'race-' + UUID.randomUUID()
        def pool = Executors.newFixedThreadPool(20)

        when:
        def futures = (1..20).collect {
            CompletableFuture.supplyAsync({ ledger.transfers.transfer(ledger.command(key, from, to, '10.00')) }, pool)
        }
        def results = futures.collect { it.get() }

        then:
        results*.transfer()*.id().unique().size() == 1
        results.count { !it.replayed() } == 1
        ledger.balanceOf(from.id()) == 990.00G
        ledger.sql.firstRow('select count(*) c from transfers where source_account_id = ?', [from.id()]).c == 1

        cleanup:
        pool.shutdown()
    }

    def "twenty parallel requests with the same key but different bodies: one wins, the rest conflict"() {
        given:
        def from = ledger.openAccount(USD, '1000.00')
        def to = ledger.openAccount(USD, '0')
        def key = 'race-' + UUID.randomUUID()
        def pool = Executors.newFixedThreadPool(20)

        when:
        def futures = (1..20).collect { i ->
            CompletableFuture.supplyAsync({
                try {
                    ledger.transfers.transfer(ledger.command(key, from, to, "${i}.00"))
                } catch (ApplicationException.IdempotencyKeyReused e) {
                    e
                }
            }, pool)
        }
        def results = futures.collect { it.get() }

        then:
        results.count { it instanceof TransferService.TransferResult && !it.replayed() } == 1
        results.count { it instanceof ApplicationException.IdempotencyKeyReused } == 19
        ledger.sql.firstRow('select count(*) c from transfers where source_account_id = ?', [from.id()]).c == 1

        cleanup:
        pool.shutdown()
    }

    def "the database refuses an unbalanced journal even if the application writes one"() {
        given:
        def account = ledger.openAccount(USD, '10.00')
        def transferId = UUID.randomUUID()
        def treasuryId = ledger.sql.firstRow("select id from accounts where kind = 'SYSTEM' and currency = 'USD'").id

        when:
        ledger.dataSource.connection.withCloseable { c ->
            c.autoCommit = false
            def sql = new groovy.sql.Sql(c)
            sql.execute('insert into transfers (id, kind, source_account_id, destination_account_id, source_amount, source_currency, destination_amount, destination_currency) values (?, ?, ?, ?, 5, ?, 5, ?)',
                    [transferId, 'TRANSFER', treasuryId, account.id(), 'USD', 'USD'])
            sql.execute('insert into journal_entries (transfer_id, account_id, currency, amount) values (?, ?, ?, ?)', [transferId, account.id(), 'USD', 5.00G])
            c.commit()
        }

        then:
        def e = thrown(java.sql.SQLException)
        e.SQLState == '23514'
        e.message.contains('does not balance')
    }

    def "the database refuses a negative customer balance"() {
        given:
        def account = ledger.openAccount(USD, '10.00')

        when:
        ledger.sql.execute('update accounts set balance = -0.01 where id = ?', [account.id()])

        then:
        def e = thrown(java.sql.SQLException)
        e.SQLState == '23514'
    }

    def "the seed data has a rate for every ordered currency pair and one treasury per currency"() {
        expect:
        ledger.sql.firstRow('select count(*) c from fx_rates').c == Currency.values().length * (Currency.values().length - 1)
        Currency.values().every { from ->
            Currency.values().findAll { it != from }.every { to ->
                ledger.sql.firstRow('select count(*) c from fx_rates where from_currency = ? and to_currency = ?', [from.name(), to.name()]).c == 1
            }
        }
        ledger.sql.firstRow("select count(*) c from accounts where kind = 'SYSTEM'").c == Currency.values().length
    }

    def "statement pages are newest first with a cursor"() {
        given:
        def from = ledger.openAccount(USD, '100.00')
        def to = ledger.openAccount(USD, '0')
        (1..5).each { ledger.transfers.transfer(ledger.command('k-' + UUID.randomUUID(), from, to, "${it}.00")) }

        when:
        def page1 = ledger.unitOfWork.execute { it.journal().statement(from.id(), 2, null) }
        def page2 = ledger.unitOfWork.execute { it.journal().statement(from.id(), 2, page1.nextCursor()) }
        def page3 = ledger.unitOfWork.execute { it.journal().statement(from.id(), 2, page2.nextCursor()) }

        then:
        page1.entries()*.amount()*.amount() == [-5.00G, -4.00G]
        page2.entries()*.amount()*.amount() == [-3.00G, -2.00G]
        page3.entries()*.amount()*.amount() == [-1.00G, 100.00G]
        page3.nextCursor() == null
        page1.entries()[0].balanceAfter().amount() == 85.00G
    }
}
