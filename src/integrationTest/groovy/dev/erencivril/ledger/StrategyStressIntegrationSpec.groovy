package dev.erencivril.ledger

import dev.erencivril.ledger.application.ApplicationException
import dev.erencivril.ledger.application.LockStrategyKind
import dev.erencivril.ledger.application.Ports
import dev.erencivril.ledger.application.RetryPolicy
import dev.erencivril.ledger.domain.Currency
import dev.erencivril.ledger.domain.DomainException
import dev.erencivril.ledger.persistence.LedgerAuditReader
import dev.erencivril.ledger.support.TestLedger
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

import static dev.erencivril.ledger.domain.Currency.*

/**
 * The same invariant suite, run once per locking strategy. After every run LedgerAudit replays the
 * whole journal and the SQL checks recompute the same facts, so a strategy that loses an update,
 * overdraws an account or leaves a transfer unbalanced fails here.
 */
class StrategyStressIntegrationSpec extends Specification {

    static final int THREADS = 32

    private static String outcomeOf(Closure<?> work) {
        try {
            work()
            return 'ok'
        } catch (DomainException.InsufficientFunds e) {
            return 'insufficient_funds'
        } catch (ApplicationException.ConcurrentUpdate e) {
            return 'concurrent_update'
        } catch (Exception e) {
            return "UNEXPECTED ${e.class.simpleName}: ${e.message}"
        }
    }

    private static List<String> runAll(List<Closure<?>> requests) {
        def pool = Executors.newFixedThreadPool(THREADS)
        def startGate = new CountDownLatch(1)
        try {
            def futures = requests.collect { request ->
                CompletableFuture.supplyAsync({ startGate.await(); outcomeOf(request) }, pool)
            }
            startGate.countDown()
            return futures.collect { it.get(180, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    def "randomized transfers across many accounts with a hot account keep every invariant: #kind"() {
        given: 'USD, EUR and JPY accounts, one hot USD account that 40% of the transfers touch'
        def ledger = new TestLedger(Ports.VelocityLimiter.unlimited(), Ports.IdempotencyCache.none(), 40, kind, new RetryPolicy(50, 1))
        def hot = ledger.openAccount(USD, '100000.00', 'hot')
        def accounts = [hot]
        24.times { accounts << ledger.openAccount(USD, '200.00') }
        8.times { accounts << ledger.openAccount(EUR, '200.00') }
        4.times { accounts << ledger.openAccount(JPY, '20000') }
        def random = new Random(20261110L) // the workload is the same every run; the interleaving is not
        def total = 1500

        def requests = (0..<total).collect { i ->
            def (from, to) = pickPair(random, accounts, hot)
            def amount = randomAmount(random, from.currency())
            ({ ledger.transfers.transfer(ledger.command("stress-${UUID.randomUUID()}", from, to, amount, from.currency())) } as Closure<?>)
        }

        when:
        def started = System.nanoTime()
        def outcomes = runAll(requests)
        def elapsedMs = (System.nanoTime() - started) / 1_000_000

        then: 'nothing failed for a reason other than a business rule or a lost race'
        outcomes.findAll { it.startsWith('UNEXPECTED') }.isEmpty()

        and: 'every request is accounted for, and both the success and the rejection paths ran'
        outcomes.size() == total
        outcomes.count { it == 'ok' } > 0
        outcomes.count { it == 'insufficient_funds' } > 0

        and: 'a lost race is only possible for the strategy that can lose one, and the retries absorb it'
        kind == LockStrategyKind.OPTIMISTIC || outcomes.count { it == 'concurrent_update' } == 0

        and: 'the transfer rows match the successful requests'
        countTransfers(ledger, accounts) == outcomes.count { it == 'ok' }

        and: 'per-currency journal sum is zero, no balance is negative, stored balance equals the replayed journal'
        LedgerAuditReader.violations(ledger.dataSource).isEmpty()
        ledger.assertLedgerConsistent()

        cleanup:
        println "STRESS strategy=${kind} transfers=${total} threads=${THREADS} ok=${outcomes.count { it == 'ok' }} " +
                "insufficient_funds=${outcomes.count { it == 'insufficient_funds' }} " +
                "concurrent_update=${outcomes.count { it == 'concurrent_update' }} retries=${ledger.transfers.retries()} " +
                "elapsed_ms=${elapsedMs as long}"
        ledger?.close()

        where:
        kind << LockStrategyKind.values()
    }

    def "400 transfers in both directions between two accounts: no deadlock, money conserved: #kind"() {
        given:
        def ledger = new TestLedger(Ports.VelocityLimiter.unlimited(), Ports.IdempotencyCache.none(), 40, kind, new RetryPolicy(50, 1))
        def a = ledger.openAccount(USD, '10000.00')
        def b = ledger.openAccount(USD, '10000.00')
        def random = new Random(42)
        def amounts = (0..<400).collect { new BigDecimal(1 + random.nextInt(5000)).movePointLeft(2) }
        def requests = (0..<400).collect { i ->
            def (from, to) = i % 2 == 0 ? [a, b] : [b, a]
            ({ ledger.transfers.transfer(ledger.command("both-${UUID.randomUUID()}", from, to, amounts[i].toPlainString())) } as Closure<?>)
        }

        when:
        def outcomes = runAll(requests)
        def sentByA = (0..<400).findAll { it % 2 == 0 }.collect { amounts[it] }.sum()
        def sentByB = (0..<400).findAll { it % 2 == 1 }.collect { amounts[it] }.sum()

        then:
        outcomes.every { it == 'ok' }
        ledger.balanceOf(a.id()) == 10000.00G - sentByA + sentByB
        ledger.balanceOf(b.id()) == 10000.00G - sentByB + sentByA
        ledger.assertLedgerConsistent()

        cleanup:
        ledger?.close()

        where:
        kind << LockStrategyKind.values()
    }

    def "300 requests of 1.00 race for an account holding 100.00: exactly 100 win and the balance ends at zero: #kind"() {
        given:
        def ledger = new TestLedger(Ports.VelocityLimiter.unlimited(), Ports.IdempotencyCache.none(), 40, kind, new RetryPolicy(50, 1))
        def poor = ledger.openAccount(USD, '100.00')
        def other = ledger.openAccount(USD, '0')
        def requests = (1..300).collect {
            ({ ledger.transfers.transfer(ledger.command("poor-${UUID.randomUUID()}", poor, other, '1.00')) } as Closure<?>)
        }

        when:
        def outcomes = runAll(requests)

        then:
        outcomes.count { it == 'ok' } == 100
        outcomes.count { it == 'insufficient_funds' } == 200
        ledger.balanceOf(poor.id()) == 0G
        ledger.balanceOf(other.id()) == 100.00G
        ledger.assertLedgerConsistent()

        cleanup:
        ledger?.close()

        where:
        kind << LockStrategyKind.values()
    }

    // ---- workload helpers ------------------------------------------------------------------------------------

    private static List pickPair(Random random, List accounts, def hot) {
        if (random.nextInt(100) < 40) {
            def other = accounts[1 + random.nextInt(accounts.size() - 1)]
            return random.nextBoolean() ? [hot, other] : [other, hot]
        }
        def from = accounts[random.nextInt(accounts.size())]
        def to = from
        while (to.id() == from.id()) {
            to = accounts[random.nextInt(accounts.size())]
        }
        [from, to]
    }

    /** 0.01 to 150.00 in a two-decimal currency, 1 to 30000 in yen. */
    private static String randomAmount(Random random, Currency currency) {
        currency == JPY ? String.valueOf(1 + random.nextInt(30000)) : new BigDecimal(1 + random.nextInt(15000)).movePointLeft(2).toPlainString()
    }

    private static long countTransfers(TestLedger ledger, List accounts) {
        def ids = accounts*.id()
        def placeholders = ids.collect { '?' }.join(',')
        ledger.sql.firstRow("select count(*) c from transfers where kind = 'TRANSFER' and source_account_id in ($placeholders)", ids).c as long
    }
}
