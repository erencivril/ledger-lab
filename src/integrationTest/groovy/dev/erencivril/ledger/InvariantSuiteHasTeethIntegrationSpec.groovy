package dev.erencivril.ledger

import dev.erencivril.ledger.application.BalanceChange
import dev.erencivril.ledger.application.Ports
import dev.erencivril.ledger.application.LockStrategyKind
import dev.erencivril.ledger.application.RetryPolicy
import dev.erencivril.ledger.application.TransferService
import dev.erencivril.ledger.application.TransferStrategy
import dev.erencivril.ledger.domain.Money
import dev.erencivril.ledger.persistence.LedgerAuditReader
import dev.erencivril.ledger.support.TestLedger
import org.testcontainers.postgresql.PostgreSQLContainer
import spock.lang.Specification

import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

import static dev.erencivril.ledger.domain.Currency.USD

/**
 * A check that never fails proves nothing. This spec runs a deliberately wrong strategy and shows that
 * the audit used by the stress tests and the benchmark does notice. It has its own PostgreSQL container,
 * because the damage it causes must not reach the ledger the other specs audit.
 */
class InvariantSuiteHasTeethIntegrationSpec extends Specification {

    /** Reads the balances without a lock and writes "what I read plus my change": a classic lost update. */
    static class NaiveStrategy implements TransferStrategy {
        final dev.erencivril.ledger.domain.TransferPlanner planner
        final CyclicBarrier bothHaveRead = new CyclicBarrier(2)

        NaiveStrategy(planner) { this.planner = planner }

        @Override
        BalanceChange changeBalances(Ports.LedgerTransaction tx, UUID sourceId, UUID destinationId, Money amount) {
            def source = tx.accounts().find(sourceId).get()
            def destination = tx.accounts().find(destinationId).get()
            bothHaveRead.await(10, TimeUnit.SECONDS)   // both transfers now hold the same, soon stale, numbers
            def plan = planner.plan(source, destination, amount)
            tx.accounts().updateBalance(sourceId, plan.sourceBalanceAfter())
            tx.accounts().updateBalance(destinationId, plan.destinationBalanceAfter())
            new BalanceChange(plan, plan.sourceBalanceAfter(), plan.destinationBalanceAfter())
        }
    }

    def "a strategy that loses an update is caught by the audit, so the invariant suite can fail"() {
        given:
        def postgres = new PostgreSQLContainer('postgres:16-alpine').withDatabaseName('ledger').withUsername('ledger').withPassword('ledger')
        postgres.start()
        def ledger = new TestLedger(Ports.VelocityLimiter.unlimited(), Ports.IdempotencyCache.none(), 4,
                LockStrategyKind.PESSIMISTIC, new RetryPolicy(1, 1), postgres)
        ledger.transfers = new TransferService(ledger.unitOfWork, new NaiveStrategy(ledger.planner), Ports.VelocityLimiter.unlimited(),
                Ports.IdempotencyCache.none(), Clock.systemUTC(), new RetryPolicy(1, 1), Ports.Pause.sleeping())
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, '0')
        assert LedgerAuditReader.violations(ledger.dataSource).isEmpty()

        when: 'two transfers of 10.00 and 20.00 run together, both reading 100.00 before either writes'
        def results = [10.00G, 20.00G].collect { amount ->
            CompletableFuture.supplyAsync { ledger.transfers.transfer(ledger.command("naive-${amount}".toString(), a, b, amount.toPlainString())) }
        }*.get(30, TimeUnit.SECONDS)
        def problems = LedgerAuditReader.violations(ledger.dataSource)

        then: 'both transfers were reported as successful, 30.00 left the account, but the balance only dropped by one of them'
        results.size() == 2
        [90.00G, 80.00G].any { it.compareTo(ledger.balanceOf(a.id())) == 0 }

        and: 'the audit finds the stored balances disagree with the journal'
        problems.size() >= 2
        problems.any { it.contains(a.id().toString()) && it.contains('journal replays to 70.00') }
        problems.any { it.contains(b.id().toString()) && it.contains('journal replays to 30.00') }

        cleanup:
        ledger?.close()
        postgres?.stop()
    }
}
