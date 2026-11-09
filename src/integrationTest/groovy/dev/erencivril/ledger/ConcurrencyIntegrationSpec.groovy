package dev.erencivril.ledger

import dev.erencivril.ledger.support.TestLedger
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static dev.erencivril.ledger.domain.Currency.*

/**
 * Hammers the same accounts from many threads in both directions. Without a fixed lock order this
 * pattern deadlocks; the first spec shows that it really does when the order is wrong.
 */
class ConcurrencyIntegrationSpec extends Specification {

    static final int THREADS = 32

    @Shared TestLedger ledger

    def setupSpec() { ledger = new TestLedger(dev.erencivril.ledger.application.Ports.VelocityLimiter.unlimited(), dev.erencivril.ledger.application.Ports.IdempotencyCache.none(), 24) }

    def cleanupSpec() {
        ledger.assertLedgerConsistent()
        ledger.close()
    }

    private static void lockRow(Connection c, UUID id) {
        def statement = c.prepareStatement('select id from accounts where id = ? for update')
        statement.setObject(1, id)
        statement.executeQuery().close()
        statement.close()
    }

    def "control: locking two rows in opposite orders deadlocks, so the test below would catch a bad lock order"() {
        given:
        def a = ledger.openAccount(USD, '10.00').id()
        def b = ledger.openAccount(USD, '10.00').id()
        def bothHoldFirstLock = new CountDownLatch(2)
        def pool = Executors.newFixedThreadPool(2)

        def lockInOrder = { UUID first, UUID second ->
            Connection c = ledger.dataSource.getConnection()
            try {
                c.setAutoCommit(false)
                lockRow(c, first)
                bothHoldFirstLock.countDown()
                bothHoldFirstLock.await(5, TimeUnit.SECONDS)
                lockRow(c, second)
                c.commit()
                return 'committed'
            } catch (SQLException e) {
                c.rollback()
                return e.getSQLState()
            } finally {
                c.close()
            }
        }

        when:
        def outcomes = [pool.submit({ lockInOrder(a, b) } as Callable), pool.submit({ lockInOrder(b, a) } as Callable)]*.get(30, TimeUnit.SECONDS)

        then: 'Postgres detects the cycle and aborts one of them with 40P01 (deadlock_detected)'
        outcomes.contains('40P01')

        cleanup:
        pool.shutdown()
    }

    def "400 concurrent transfers in both directions between two accounts: no deadlock, money conserved"() {
        given:
        def a = ledger.openAccount(USD, '10000.00')
        def b = ledger.openAccount(USD, '10000.00')
        def total = 400
        def random = new Random(42)
        def amounts = (0..<total).collect { new BigDecimal(1 + random.nextInt(5000)).movePointLeft(2) } // 0.01 .. 50.00
        def pool = Executors.newFixedThreadPool(THREADS)
        def startGate = new CountDownLatch(1)

        when:
        def started = System.nanoTime()
        def futures = (0..<total).collect { i ->
            CompletableFuture.supplyAsync({
                startGate.await()
                def (from, to) = i % 2 == 0 ? [a, b] : [b, a]
                try {
                    ledger.transfers.transfer(ledger.command("c-${UUID.randomUUID()}", from, to, amounts[i].toPlainString()))
                    'ok'
                } catch (Exception e) {
                    "${e.class.simpleName}: ${e.message}"
                }
            }, pool)
        }
        startGate.countDown()
        def outcomes = futures.collect { it.get(120, TimeUnit.SECONDS) }
        def elapsedMs = (System.nanoTime() - started) / 1_000_000

        def sentByA = (0..<total).findAll { it % 2 == 0 }.collect { amounts[it] }.sum()
        def sentByB = (0..<total).findAll { it % 2 == 1 }.collect { amounts[it] }.sum()

        then: 'every transfer succeeded, so there were no deadlocks, lock timeouts or lost updates'
        outcomes.every { it == 'ok' }

        and: 'the total is conserved and each balance equals the opening balance plus the net flow'
        ledger.balanceOf(a.id()) + ledger.balanceOf(b.id()) == 20000.00G
        ledger.balanceOf(a.id()) == 10000.00G - sentByA + sentByB
        ledger.balanceOf(b.id()) == 10000.00G - sentByB + sentByA
        ledger.sql.firstRow('select count(*) c from transfers where kind = ? and (source_account_id in (?, ?))', ['TRANSFER', a.id(), b.id()]).c == total

        and: 'the journal agrees with the balances'
        ledger.assertLedgerConsistent()

        cleanup:
        pool.shutdown()
        println "CONCURRENCY transfers=${total} threads=${THREADS} direction=both ok=${outcomes.count { it == 'ok' }} " +
                "deadlocks=${outcomes.count { it.contains('40P01') || it.toLowerCase().contains('deadlock') }} elapsed_ms=${elapsedMs as long} " +
                "tps=${(total * 1000 / elapsedMs) as long} total_before=20000.00 total_after=${ledger.balanceOf(a.id()) + ledger.balanceOf(b.id())}"
    }

    def "contention on a poor account: only the funded transfers succeed and no balance goes negative"() {
        given:
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, '0')
        def pool = Executors.newFixedThreadPool(THREADS)
        def startGate = new CountDownLatch(1)

        when: '300 requests of 1.00 each race for a balance that only covers 100 of them'
        def futures = (1..300).collect {
            CompletableFuture.supplyAsync({
                startGate.await()
                try {
                    ledger.transfers.transfer(ledger.command("p-${UUID.randomUUID()}", a, b, '1.00'))
                    'ok'
                } catch (dev.erencivril.ledger.domain.DomainException.InsufficientFunds e) {
                    'insufficient'
                } catch (Exception e) {
                    "unexpected ${e}"
                }
            }, pool)
        }
        startGate.countDown()
        def outcomes = futures.collect { it.get(120, TimeUnit.SECONDS) }

        then:
        outcomes.count { it == 'ok' } == 100
        outcomes.count { it == 'insufficient' } == 200
        ledger.balanceOf(a.id()) == 0G
        ledger.balanceOf(b.id()) == 100.00G

        cleanup:
        pool.shutdown()
    }

    def "200 concurrent cross-currency transfers in both directions keep every currency balanced"() {
        given:
        def usd = ledger.openAccount(USD, '5000.00')
        def eur = ledger.openAccount(EUR, '5000.00')
        def pool = Executors.newFixedThreadPool(THREADS)
        def startGate = new CountDownLatch(1)

        when:
        def futures = (0..<200).collect { i ->
            CompletableFuture.supplyAsync({
                startGate.await()
                try {
                    i % 2 == 0 ?
                            ledger.transfers.transfer(ledger.command("x-${UUID.randomUUID()}", usd, eur, '10.00', USD)) :
                            ledger.transfers.transfer(ledger.command("x-${UUID.randomUUID()}", eur, usd, '10.00', EUR))
                    'ok'
                } catch (Exception e) {
                    "${e.class.simpleName}: ${e.message}"
                }
            }, pool)
        }
        startGate.countDown()
        def outcomes = futures.collect { it.get(120, TimeUnit.SECONDS) }

        then:
        outcomes.every { it == 'ok' }
        // 100 x (USD 10.00 -> EUR 9.20) and 100 x (EUR 10.00 -> USD 10.87)
        ledger.balanceOf(usd.id()) == 5000.00G - 1000.00G + 1087.00G
        ledger.balanceOf(eur.id()) == 5000.00G + 920.00G - 1000.00G
        ledger.assertLedgerConsistent()

        cleanup:
        pool.shutdown()
    }
}
