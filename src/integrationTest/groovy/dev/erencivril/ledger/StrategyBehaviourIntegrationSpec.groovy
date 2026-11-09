package dev.erencivril.ledger

import dev.erencivril.ledger.application.ApplicationException
import dev.erencivril.ledger.application.LockStrategyKind
import dev.erencivril.ledger.application.Ports
import dev.erencivril.ledger.application.RetryPolicy
import dev.erencivril.ledger.application.TransferService
import dev.erencivril.ledger.domain.Money
import dev.erencivril.ledger.support.TestLedger
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function

import static dev.erencivril.ledger.domain.Currency.USD

/** What is specific to each strategy, shown with deterministic interleavings instead of luck. */
class StrategyBehaviourIntegrationSpec extends Specification {

    @Shared TestLedger ledger

    def setupSpec() { ledger = new TestLedger() }

    def cleanupSpec() {
        ledger.assertLedgerConsistent()
        ledger.close()
    }

    private static Money usd(String amount) { new Money(new BigDecimal(amount), USD) }

    private long versionOf(UUID id) { ledger.sql.firstRow('select version from accounts where id = ?', [id]).version }

    def "every strategy moves the version of both accounts forward by one per transfer: #kind"() {
        given:
        def own = new TestLedger(Ports.VelocityLimiter.unlimited(), Ports.IdempotencyCache.none(), 8, kind)
        def a = own.openAccount(USD, '100.00')
        def b = own.openAccount(USD, '0')
        def versionAfterOpening = own.sql.firstRow('select version from accounts where id = ?', [a.id()]).version

        when:
        3.times { own.transfers.transfer(own.command("v-${UUID.randomUUID()}", a, b, '1.00')) }

        then:
        own.sql.firstRow('select version from accounts where id = ?', [a.id()]).version == versionAfterOpening + 3
        own.sql.firstRow('select version from accounts where id = ?', [b.id()]).version == 3

        cleanup:
        own?.close()

        where:
        kind << LockStrategyKind.values()
    }

    def "optimistic: a write based on an out-of-date read is refused, and nothing is overwritten"() {
        given:
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, '0')

        when: 'transaction 1 reads account A, then a competing transfer commits, then transaction 1 tries to write'
        def accepted = ledger.unitOfWork.execute { tx ->
            def read = tx.accounts().findVersioned(a.id()).get()
            ledger.transfers.transfer(ledger.command("competitor-${UUID.randomUUID()}", a, b, '10.00'))
            tx.accounts().updateBalanceIfVersion(a.id(), usd('50.00'), read.version())
        }

        then: 'the write found a newer version and changed no row'
        !accepted
        ledger.balanceOf(a.id()) == 90.00G
        ledger.balanceOf(b.id()) == 10.00G
    }

    def "optimistic: a write based on the current read is accepted and moves the version"() {
        given:
        def a = ledger.openAccount(USD, '100.00')

        when: 'this raw write has no journal entry, so it is rolled back at the end to keep the ledger consistent'
        def seen = rolledBack { tx ->
            def read = tx.accounts().findVersioned(a.id()).get()
            def accepted = tx.accounts().updateBalanceIfVersion(a.id(), usd('99.00'), read.version())
            [accepted: accepted, versionBefore: read.version(), versionAfter: tx.accounts().findVersioned(a.id()).get().version()]
        }

        then:
        seen.accepted
        seen.versionAfter == seen.versionBefore + 1
    }

    def "optimistic: the service loses one race on purpose, retries once and ends with correct balances"() {
        given:
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, '0')
        def pauses = []
        def interfering = unitOfWorkThatRunsOnFirstWrite {
            ledger.transfers.transfer(ledger.command("competitor-${UUID.randomUUID()}", a, b, '10.00'))
        }
        def service = new TransferService(
                interfering,
                LockStrategyKind.OPTIMISTIC.create(ledger.planner),
                Ports.VelocityLimiter.unlimited(),
                Ports.IdempotencyCache.none(),
                Clock.systemUTC(),
                new RetryPolicy(3, 4),
                { long millis -> pauses << millis } as Ports.Pause)

        when:
        def result = service.transfer(ledger.command("mine-${UUID.randomUUID()}", a, b, '5.00'))

        then: 'attempt 1 lost, attempt 2 won'
        !result.replayed()
        service.retries() == 1
        pauses.size() == 1
        pauses[0] >= 0 && pauses[0] <= 4

        and: 'both transfers are applied exactly once'
        ledger.balanceOf(a.id()) == 85.00G
        ledger.balanceOf(b.id()) == 15.00G
        ledger.sql.firstRow("select count(*) c from transfers where kind = 'TRANSFER' and source_account_id = ?", [a.id()]).c == 2

        and: 'the failed attempt left no half-written journal'
        ledger.sql.firstRow('select count(*) c from journal_entries where account_id = ?', [a.id()]).c == 3
        ledger.assertLedgerConsistent()
    }

    def "optimistic: when every attempt loses, the caller gets a ConcurrentUpdate and no money moved"() {
        given:
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, '0')
        def always = unitOfWorkThatRunsOnFirstWrite(true) {
            ledger.transfers.transfer(ledger.command("competitor-${UUID.randomUUID()}", a, b, '1.00'))
        }
        def service = new TransferService(
                always, LockStrategyKind.OPTIMISTIC.create(ledger.planner), Ports.VelocityLimiter.unlimited(),
                Ports.IdempotencyCache.none(), Clock.systemUTC(), new RetryPolicy(3, 1), { long m -> } as Ports.Pause)
        def key = "doomed-${UUID.randomUUID()}".toString()

        when:
        service.transfer(ledger.command(key, a, b, '5.00'))

        then:
        thrown(ApplicationException.ConcurrentUpdate)
        service.retries() == 2

        and: 'only the three competitors moved money, and the key is free to use again'
        ledger.balanceOf(a.id()) == 97.00G
        ledger.sql.firstRow('select count(*) c from idempotency_keys where idempotency_key = ?', [key]).c == 0
    }

    def "atomic: the debit succeeds for exactly the whole balance and fails for one cent more"() {
        given:
        def a = ledger.openAccount(USD, '100.00')

        when: 'raw writes without journal entries, rolled back at the end'
        def seen = rolledBack { tx ->
            [tooMuch    : tx.accounts().debitIfFunded(a.id(), usd('100.01')),
             exact      : tx.accounts().debitIfFunded(a.id(), usd('100.00')),
             nothingLeft: tx.accounts().debitIfFunded(a.id(), usd('0.01'))]
        }

        then:
        !seen.tooMuch.isPresent()
        seen.exact.get() == usd('0.00')
        !seen.nothingLeft.isPresent()
        ledger.balanceOf(a.id()) == 100.00G
    }

    def "atomic: credit returns the new balance"() {
        given:
        def a = ledger.openAccount(USD, '10.00')

        expect:
        rolledBack { tx -> tx.accounts().credit(a.id(), usd('2.50')) } == usd('12.50')
        ledger.balanceOf(a.id()) == 10.00G
    }

    private static class Rollback extends RuntimeException {
        Rollback() { super('rolled back on purpose', null, false, false) }
    }

    /** Runs the work in a transaction, hands back what it returned, and rolls the transaction back. */
    private <T> T rolledBack(Closure<T> work) {
        T result = null
        try {
            ledger.unitOfWork.execute { tx ->
                result = work(tx)
                throw new Rollback()
            }
        } catch (Rollback ignored) {
            // expected: this is how the transaction is undone
        }
        result
    }

    /**
     * A unit of work whose accounts repository runs {@code interference} just before the first (or, with
     * {@code everyAttempt}, every) updateBalanceIfVersion call. The interference commits in its own transaction,
     * so the optimistic write that follows finds a newer version.
     */
    private Ports.UnitOfWork unitOfWorkThatRunsOnFirstWrite(boolean everyAttempt = false, Closure interference) {
        def fired = new AtomicBoolean(false)
        return { Function work ->
            ledger.unitOfWork.execute { tx ->
                def real = tx.accounts()
                def accounts = Proxy.newProxyInstance(
                        Ports.AccountRepository.classLoader,
                        [Ports.AccountRepository] as Class[],
                        { Object proxy, Method method, Object[] args ->
                            if (method.name == 'updateBalanceIfVersion' && (everyAttempt || fired.compareAndSet(false, true))) {
                                interference()
                            }
                            try {
                                return method.invoke(real, args)
                            } catch (InvocationTargetException e) {
                                throw e.targetException
                            }
                        } as InvocationHandler)
                work.apply([
                        accounts   : { accounts },
                        transfers  : { tx.transfers() },
                        journal    : { tx.journal() },
                        idempotency: { tx.idempotency() },
                        fxRates    : { tx.fxRates() },
                ] as Ports.LedgerTransaction)
            }
        } as Ports.UnitOfWork
    }
}
