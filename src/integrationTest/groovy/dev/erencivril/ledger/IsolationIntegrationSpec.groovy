package dev.erencivril.ledger

import dev.erencivril.ledger.application.RetryPolicy
import dev.erencivril.ledger.support.Infra
import groovy.sql.Sql
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static dev.erencivril.ledger.domain.Currency.USD

/**
 * Isolation levels shown on real PostgreSQL. docs/isolation.md explains each experiment.
 *
 * The experiments use their own small table, not the ledger tables. They write balances without journal
 * entries on purpose, which would trip the ledger audit that other specs run over the whole database.
 *
 * Where an anomaly needs a specific order of events, the test drives two connections by hand from one
 * thread: connection 1 does this, connection 2 does that, in a fixed order. That makes the result the
 * same on every run, with no sleeps and no luck. Where one session must block on another, a second thread
 * is used and the test waits until PostgreSQL itself reports the session as waiting for a lock.
 */
class IsolationIntegrationSpec extends Specification {

    @Shared Sql admin

    def setupSpec() {
        admin = new Sql(DriverManager.getConnection(Infra.jdbcUrl(), Infra.POSTGRES.username, Infra.POSTGRES.password))
        admin.execute('create table lab_accounts (id uuid primary key, balance numeric(19,2) not null check (balance >= 0))')
    }

    def cleanupSpec() {
        admin.execute('drop table lab_accounts')
        admin.close()
    }

    private UUID newAccount(String balance) {
        def id = UUID.randomUUID()
        admin.executeInsert('insert into lab_accounts (id, balance) values (?, ?)', [id, new BigDecimal(balance)])
        id
    }

    private BigDecimal committedBalance(UUID id) { admin.firstRow('select balance from lab_accounts where id = ?', [id]).balance }

    private BigDecimal committedTotal(UUID a, UUID b) { admin.firstRow('select sum(balance) total from lab_accounts where id in (?, ?)', [a, b]).total }

    private static Connection open(int isolation) {
        Connection c = DriverManager.getConnection(Infra.jdbcUrl(), Infra.POSTGRES.username, Infra.POSTGRES.password)
        c.autoCommit = false
        c.transactionIsolation = isolation
        c
    }

    private static BigDecimal balance(Connection c, UUID id) {
        new Sql(c).firstRow('select balance from lab_accounts where id = ?', [id]).balance
    }

    private static BigDecimal totalOf(Connection c, UUID a, UUID b) {
        new Sql(c).firstRow('select sum(balance) total from lab_accounts where id in (?, ?)', [a, b]).total
    }

    private static void write(Connection c, UUID id, BigDecimal newBalance) {
        new Sql(c).executeUpdate('update lab_accounts set balance = ? where id = ?', [newBalance, id])
    }

    private static void withdraw(Connection c, UUID id, BigDecimal amount) {
        new Sql(c).executeUpdate('update lab_accounts set balance = balance - ? where id = ?', [amount, id])
    }

    // ---- 1. lost update ---------------------------------------------------------------------------------

    def "lost update under READ COMMITTED: two naive read-modify-write withdrawals, and one of them disappears"() {
        given: 'an account with 100.00 and two sessions that each want to withdraw'
        def account = newAccount('100.00')
        def t1 = open(Connection.TRANSACTION_READ_COMMITTED)
        def t2 = open(Connection.TRANSACTION_READ_COMMITTED)

        when: 'both read 100.00, then each writes "what I read minus my amount"'
        def seenByT1 = balance(t1, account)
        def seenByT2 = balance(t2, account)
        write(t1, account, seenByT1 - 30.00G)
        t1.commit()
        write(t2, account, seenByT2 - 50.00G)   // READ COMMITTED lets this overwrite T1's committed row
        t2.commit()

        then: '80.00 was withdrawn but the balance only dropped by 50.00: 30.00 has appeared from nowhere'
        seenByT1 == 100.00G
        seenByT2 == 100.00G
        committedBalance(account) == 50.00G

        cleanup:
        t1?.close()
        t2?.close()
    }

    def "lost update fix 1: let the database do the arithmetic, so the second write builds on the first"() {
        given:
        def account = newAccount('100.00')
        def t1 = open(Connection.TRANSACTION_READ_COMMITTED)
        def t2 = open(Connection.TRANSACTION_READ_COMMITTED)

        when: 'the same two withdrawals, as "balance = balance - x" statements'
        withdraw(t1, account, 30.00G)
        t1.commit()
        withdraw(t2, account, 50.00G)
        t2.commit()

        then:
        committedBalance(account) == 20.00G

        cleanup:
        t1?.close()
        t2?.close()
    }

    def "lost update fix 2: SELECT FOR UPDATE makes the second reader wait, and it then reads the new balance"() {
        given:
        def account = newAccount('100.00')
        def t1 = open(Connection.TRANSACTION_READ_COMMITTED)
        def t1Balance = new Sql(t1).firstRow('select balance from lab_accounts where id = ? for update', [account]).balance
        def t2Pid = new java.util.concurrent.CompletableFuture<Integer>()
        def pool = Executors.newSingleThreadExecutor()

        when: 'session 2 tries the same locking read and blocks'
        def second = CompletableFuture.supplyAsync({
            Connection t2 = open(Connection.TRANSACTION_READ_COMMITTED)
            try {
                t2Pid.complete(new Sql(t2).firstRow('select pg_backend_pid() pid').pid as Integer)
                def seen = new Sql(t2).firstRow('select balance from lab_accounts where id = ? for update', [account]).balance
                write(t2, account, seen - 50.00G)
                t2.commit()
                seen
            } finally {
                t2.close()
            }
        }, pool)
        awaitWaitingForLock(t2Pid.get(10, TimeUnit.SECONDS))

        and: 'session 1 writes and commits, which releases the lock'
        write(t1, account, t1Balance - 30.00G)
        t1.commit()

        then: 'session 2 read the balance after session 1 committed, so both withdrawals count'
        second.get(10, TimeUnit.SECONDS) == 70.00G
        committedBalance(account) == 20.00G

        cleanup:
        t1?.close()
        pool?.shutdown()
    }

    def "lost update under REPEATABLE READ: PostgreSQL aborts the second writer with 40001 instead of overwriting"() {
        given:
        def account = newAccount('100.00')
        def t1 = open(Connection.TRANSACTION_REPEATABLE_READ)
        def t2 = open(Connection.TRANSACTION_REPEATABLE_READ)

        when:
        def seenByT1 = balance(t1, account)
        def seenByT2 = balance(t2, account)
        write(t1, account, seenByT1 - 30.00G)
        t1.commit()
        write(t2, account, seenByT2 - 50.00G)

        then: 'the row changed after T2 took its snapshot, so T2 may not update it'
        def e = thrown(SQLException)
        e.SQLState == '40001'

        and: 'only the first withdrawal happened, nothing was lost'
        committedBalance(account) == 70.00G

        cleanup:
        t1?.close()
        t2?.close()
    }

    // ---- 2. write skew ----------------------------------------------------------------------------------

    /**
     * The rule: an owner's two accounts must hold at least 100.00 together. The database does not know the
     * rule; each transaction checks it in application code before it withdraws 60.00 from one account.
     */
    private List twoAccountsWith100Each() {
        [newAccount('100.00'), newAccount('100.00')]
    }

    def "write skew under REPEATABLE READ: two transactions each keep the rule, together they break it"() {
        given:
        def (a, b) = twoAccountsWith100Each()
        def t1 = open(Connection.TRANSACTION_REPEATABLE_READ)
        def t2 = open(Connection.TRANSACTION_REPEATABLE_READ)

        when: 'both check the rule on their own snapshot (200.00 - 60.00 >= 100.00), then withdraw from different accounts'
        def okForT1 = totalOf(t1, a, b) - 60.00G >= 100.00G
        def okForT2 = totalOf(t2, a, b) - 60.00G >= 100.00G
        withdraw(t1, a, 60.00G)
        withdraw(t2, b, 60.00G)   // a different row, so no write-write conflict and nothing makes T2 wait or fail
        t1.commit()
        t2.commit()

        then: 'each transaction was right on its own snapshot, and both committed'
        okForT1
        okForT2

        and: 'the rule is broken: 80.00 is left and the minimum was 100.00'
        committedTotal(a, b) == 80.00G

        cleanup:
        t1?.close()
        t2?.close()
    }

    def "write skew under SERIALIZABLE: the same interleaving is stopped, the second committer gets 40001"() {
        given:
        def (a, b) = twoAccountsWith100Each()
        def t1 = open(Connection.TRANSACTION_SERIALIZABLE)
        def t2 = open(Connection.TRANSACTION_SERIALIZABLE)

        when:
        totalOf(t1, a, b)
        totalOf(t2, a, b)
        withdraw(t1, a, 60.00G)
        withdraw(t2, b, 60.00G)
        t1.commit()
        t2.commit()

        then: 'PostgreSQL saw the two transactions depend on each other in a cycle and refused one'
        def e = thrown(SQLException)
        e.SQLState == '40001'

        and: 'only one withdrawal happened, so the rule still holds'
        committedTotal(a, b) == 140.00G

        cleanup:
        t1?.close()
        t2?.close()
    }

    def "write skew fix without SERIALIZABLE: lock both rows first, then check the rule"() {
        given:
        def (a, b) = twoAccountsWith100Each()
        def t1 = open(Connection.TRANSACTION_READ_COMMITTED)
        new Sql(t1).rows('select id from lab_accounts where id in (?, ?) order by id for update', [a, b])
        def t2Pid = new CompletableFuture<Integer>()
        def pool = Executors.newSingleThreadExecutor()

        when: 'T2 wants the same locks and waits'
        def second = CompletableFuture.supplyAsync({
            Connection t2 = open(Connection.TRANSACTION_READ_COMMITTED)
            try {
                t2Pid.complete(new Sql(t2).firstRow('select pg_backend_pid() pid').pid as Integer)
                new Sql(t2).rows('select id from lab_accounts where id in (?, ?) order by id for update', [a, b])
                def allowed = totalOf(t2, a, b) - 60.00G >= 100.00G
                if (allowed) {
                    withdraw(t2, b, 60.00G)
                }
                t2.commit()
                allowed
            } finally {
                t2.close()
            }
        }, pool)
        awaitWaitingForLock(t2Pid.get(10, TimeUnit.SECONDS))
        def allowedForT1 = totalOf(t1, a, b) - 60.00G >= 100.00G
        withdraw(t1, a, 60.00G)
        t1.commit()

        then: 'T1 was allowed; T2 then saw 140.00 and refused itself'
        allowedForT1
        !second.get(10, TimeUnit.SECONDS)
        committedTotal(a, b) == 140.00G

        cleanup:
        t1?.close()
        pool?.shutdown()
    }

    // ---- 3. SERIALIZABLE with 40001 retry and jitter -----------------------------------------------------

    def "SERIALIZABLE with retry and jitter: eight concurrent withdrawals end with exactly the number the rule allows"() {
        given: 'rule: at least 100.00 together; 200.00 now; each caller wants to withdraw 30.00, so exactly 3 can succeed'
        def (a, b) = twoAccountsWith100Each()
        def callers = 8
        def firstReadsDone = new CyclicBarrier(callers)
        def serializationFailures = new AtomicInteger()
        def policy = new RetryPolicy(40, 2)
        def pool = Executors.newFixedThreadPool(callers)

        when:
        def futures = (0..<callers).collect { n ->
            CompletableFuture.supplyAsync({
                def random = new Random(n)
                def target = n % 2 == 0 ? a : b
                for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
                    Connection c = open(Connection.TRANSACTION_SERIALIZABLE)
                    try {
                        def allowed = totalOf(c, a, b) - 30.00G >= 100.00G
                        if (attempt == 1) {
                            firstReadsDone.await(30, TimeUnit.SECONDS) // everyone reads before anyone writes: forces conflicts
                        }
                        if (allowed) {
                            withdraw(c, target, 30.00G)
                        }
                        c.commit()
                        return allowed ? 'withdrawn' : 'refused'
                    } catch (SQLException e) {
                        c.rollback()
                        if (e.SQLState != '40001') {
                            throw e
                        }
                        serializationFailures.incrementAndGet()
                        Thread.sleep(policy.jitterMillis(attempt, random)) // jitter: callers that failed together do not retry together
                    } finally {
                        c.close()
                    }
                }
                'gave up'
            }, pool)
        }
        def outcomes = futures.collect { it.get(60, TimeUnit.SECONDS) }

        then: 'nobody gave up, and the rule allowed exactly three withdrawals'
        outcomes.count { it == 'gave up' } == 0
        outcomes.count { it == 'withdrawn' } == 3
        outcomes.count { it == 'refused' } == 5

        and: 'the rule holds: 200.00 - 3 x 30.00'
        committedTotal(a, b) == 110.00G

        and: 'serialization failures really happened and were retried away'
        serializationFailures.get() >= 1

        cleanup:
        pool?.shutdown()
        println "ISOLATION serializable_retry callers=${callers} withdrawn=${outcomes.count { it == 'withdrawn' }} " +
                "refused=${outcomes.count { it == 'refused' }} serialization_failures_40001=${serializationFailures.get()}"
    }

    /**
     * Waits until PostgreSQL reports the session as waiting for a lock. This looks at the server's own state,
     * so there is no guess about timing; the loop only repeats the question.
     */
    private void awaitWaitingForLock(int pid) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!admin.firstRow("select count(*) c from pg_stat_activity where pid = ? and wait_event_type = 'Lock'", [pid]).c) {
            assert System.nanoTime() < deadline: "session $pid never started waiting for a lock"
            Thread.onSpinWait()
        }
    }
}
