package dev.erencivril.ledger

import dev.erencivril.ledger.application.*
import dev.erencivril.ledger.application.Ports.*
import dev.erencivril.ledger.domain.*
import dev.erencivril.ledger.risk.RuleSetLoader
import dev.erencivril.ledger.support.TestLedger
import spock.lang.Specification
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Clock
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import static dev.erencivril.ledger.domain.Currency.USD

class HoldLockRegressionSpec extends Specification {
    def "opposite placements share the sorted account protocol: #kind, second hold=#secondHold"() {
        given:
        def ledger = new TestLedger()
        ledger.enableRisk(RiskHoldIntegrationSpec.RULES)
        def pair = [ledger.openAccount(USD, '1000'), ledger.openAccount(USD, '1000')].sort { it.id() }
        def low = pair[0]
        def high = pair[1]
        def heldBefore = ledger.holdBalance(USD)
        def firstAcquired = new CountDownLatch(1)
        def secondAttempt = new CountDownLatch(1)
        def secondAcquired = new CountDownLatch(1)
        def proceed = new CountDownLatch(1)
        def deadlocks = new ConcurrentLinkedQueue<String>()
        def engine = new RuleSetLoader(ledger.unitOfWork).engineFor(9001)
        def rates = ledger.unitOfWork.execute { tx -> new FxRateTable(tx.fxRates().findAll()) }
        def risk = new LedgerRiskAssessor(ledger.unitOfWork, engine, rates, Clock.systemUTC())
        def makeService = { boolean leader ->
            UnitOfWork decorated = new UnitOfWork() {
                @Override
                public <T> T execute(Function<LedgerTransaction, T> work) {
                    try {
                        return ledger.unitOfWork.execute { tx ->
                            def seen = new AtomicBoolean()
                            def accounts = Proxy.newProxyInstance(AccountRepository.classLoader, [AccountRepository] as Class[],
                                    { proxy, method, args ->
                                        boolean pauseHere = method.name in ['findForUpdate', 'updateBalanceIfVersion', 'debitIfFunded', 'credit'] && seen.compareAndSet(false, true)
                                        if (pauseHere && !leader) secondAttempt.countDown()
                                        def result
                                        try { result = method.invoke(tx.accounts(), args) }
                                        catch (InvocationTargetException e) { throw e.cause }
                                        if (pauseHere) {
                                            (leader ? firstAcquired : secondAcquired).countDown()
                                            assert proceed.await(15, TimeUnit.SECONDS)
                                        }
                                        result
                                    } as java.lang.reflect.InvocationHandler) as AccountRepository
                            def wrapped = Proxy.newProxyInstance(LedgerTransaction.classLoader, [LedgerTransaction] as Class[],
                                    { proxy, method, args ->
                                        if (method.name == 'accounts') return accounts
                                        try { method.invoke(tx, args) } catch (InvocationTargetException e) { throw e.cause }
                                    } as java.lang.reflect.InvocationHandler) as LedgerTransaction
                            work.apply(wrapped)
                        }
                    } catch (RuntimeException e) {
                        if (e.message?.contains('40P01')) deadlocks.add('40P01')
                        for (Throwable c = e; c != null; c = c.cause) {
                            if (c instanceof java.sql.SQLException && c.SQLState == '40P01') deadlocks.add('40P01')
                        }
                        throw e
                    }
                }
            }
            new TransferService(decorated, kind.create(ledger.planner), VelocityLimiter.unlimited(), IdempotencyCache.none(),
                    Clock.systemUTC(), new RetryPolicy(5, 1), Pause.sleeping(), risk, ledger.holds)
        }
        def firstService = makeService(true)
        def secondService = makeService(false)
        def pool = Executors.newFixedThreadPool(2)

        when:
        def first = pool.submit({ firstService.transfer(ledger.command(UUID.randomUUID().toString(), high, low, '150')) } as Callable)
        assert firstAcquired.await(10, TimeUnit.SECONDS)
        def second = pool.submit({ secondService.transfer(ledger.command(UUID.randomUUID().toString(), low, high, secondHold ? '150' : '1')) } as Callable)
        assert secondAttempt.await(10, TimeUnit.SECONDS)
        // Either the old protocol acquired a different first row, or the sorted protocol
        // is actually waiting in PostgreSQL on the first row. No timing sleeps/barrier guess.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        boolean waiting = false
        while (secondAcquired.count != 0 && !(waiting = ledger.sql.firstRow("select count(*) c from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' and query like '%accounts%'").c > 0) && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assert secondAcquired.count == 0 || waiting
        proceed.countDown()
        def results = [first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)]

        then:
        deadlocks.isEmpty()
        firstService.retries() == 0
        secondService.retries() == (kind == LockStrategyKind.OPTIMISTIC && !secondHold ? 1 : 0)
        results*.transfer()*.status() == [TransferStatus.PENDING, secondHold ? TransferStatus.PENDING : TransferStatus.COMPLETED]
        ledger.balanceOf(high.id()) == (secondHold ? 850G : 851G)
        ledger.balanceOf(low.id()) == (secondHold ? 850G : 999G)
        ledger.holdBalance(USD) - heldBefore == (secondHold ? 300G : 150G)
        ledger.assertLedgerConsistent()

        cleanup:
        proceed?.countDown()
        pool?.shutdownNow()
        pool?.awaitTermination(30, TimeUnit.SECONDS)
        ledger?.close()

        where:
        [kind, secondHold] << [LockStrategyKind.values().toList(), [true, false]].combinations()
    }
}
