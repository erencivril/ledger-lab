package dev.erencivril.ledger

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import dev.erencivril.ledger.application.ApplicationException
import dev.erencivril.ledger.application.LockStrategyKind
import dev.erencivril.ledger.support.ApiClient
import dev.erencivril.ledger.support.TestLedger
import org.testcontainers.postgresql.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification

import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static dev.erencivril.ledger.domain.Currency.USD

/**
 * The failure-mode table in docs/failure-modes.md links to these specs. Each one forces the failure
 * and then checks the ledger, so "it survives" is shown and not claimed.
 */
class FailureModesIntegrationSpec extends Specification {

    @Shared TestLedger ledger

    def setupSpec() { ledger = new TestLedger() }

    def cleanupSpec() {
        ledger.assertLedgerConsistent()
        ledger.close()
    }

    def "crash mid-transfer (application): an exception after the balances changed leaves no trace and the key stays free"() {
        given:
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, '0')
        def key = "crash-${UUID.randomUUID()}".toString()
        def strategy = LockStrategyKind.PESSIMISTIC.create(ledger.planner)

        when: 'the process dies after the debit and the credit were written but before the commit'
        ledger.unitOfWork.execute { tx ->
            tx.idempotency().tryClaim(key, 'fingerprint')
            strategy.changeBalances(tx, a.id(), b.id(), new dev.erencivril.ledger.domain.Money(10.00G, USD))
            throw new IllegalStateException('simulated crash')
        }

        then:
        thrown(IllegalStateException)
        ledger.balanceOf(a.id()) == 100.00G
        ledger.balanceOf(b.id()) == 0G
        ledger.sql.firstRow('select count(*) c from idempotency_keys where idempotency_key = ?', [key]).c == 0

        and: 'the client retries with the same key and the transfer happens once'
        ledger.transfers.transfer(ledger.command(key, a, b, '10.00')).transfer() != null
        ledger.balanceOf(a.id()) == 90.00G
        ledger.balanceOf(b.id()) == 10.00G
    }

    def "crash mid-transfer (database connection): terminating the backend before COMMIT rolls everything back"() {
        given:
        def a = ledger.openAccount(USD, '100.00')
        def victim = ledger.dataSource.getConnection()
        victim.autoCommit = false
        victim.prepareStatement('update accounts set balance = balance - 10 where id = ?').with { it.setObject(1, a.id()); it.executeUpdate(); it.close() }
        def pid = victim.createStatement().with { def rs = it.executeQuery('select pg_backend_pid()'); rs.next(); rs.getInt(1) }

        when: 'another session kills the backend, as a crash or a failover would'
        def killer = ledger.dataSource.getConnection()
        killer.prepareStatement('select pg_terminate_backend(?)').with { it.setInt(1, pid); it.executeQuery().close(); it.close() }
        killer.close()
        victim.commit()

        then: 'the commit cannot succeed'
        thrown(java.sql.SQLException)

        and: 'the half-done debit never became visible'
        ledger.balanceOf(a.id()) == 100.00G

        cleanup:
        try { victim.close() } catch (Exception ignored) { }
    }

    def "timeout after commit: the response is lost, the client retries with the same key and gets the original transfer"() {
        given:
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, '0')
        def key = "lost-${UUID.randomUUID()}".toString()

        when: 'the first request commits but its answer never reaches the client, so the result is thrown away'
        ledger.transfers.transfer(ledger.command(key, a, b, '25.00'))
        def retry = ledger.transfers.transfer(ledger.command(key, a, b, '25.00'))

        then:
        retry.replayed()
        ledger.balanceOf(a.id()) == 75.00G
        ledger.balanceOf(b.id()) == 25.00G
        ledger.sql.firstRow("select count(*) c from transfers where kind = 'TRANSFER' and source_account_id = ?", [a.id()]).c == 1
    }

    def "deadlock: PostgreSQL aborts one transaction with 40P01 and the application reports it as a retryable ConcurrentUpdate"() {
        given:
        def a = ledger.openAccount(USD, '10.00').id()
        def b = ledger.openAccount(USD, '10.00').id()
        def bothHoldTheirFirstLock = new CountDownLatch(2)
        def lockInOrder = { UUID first, UUID second ->
            try {
                ledger.unitOfWork.execute { tx ->
                    tx.accounts().findForUpdate(first)
                    bothHoldTheirFirstLock.countDown()
                    bothHoldTheirFirstLock.await(10, TimeUnit.SECONDS)
                    tx.accounts().findForUpdate(second)
                    'committed'
                }
            } catch (ApplicationException.ConcurrentUpdate e) {
                e.message
            }
        }

        when:
        def outcomes = [CompletableFuture.supplyAsync { lockInOrder(a, b) }, CompletableFuture.supplyAsync { lockInOrder(b, a) }]
                *.get(30, TimeUnit.SECONDS)

        then:
        outcomes.count { it == 'committed' } == 1
        outcomes.find { it != 'committed' }.contains('40P01')
    }

    def "database down: requests get 503, nothing moves, and the same Idempotency-Key works after the database is back"() {
        given: 'the application runs against its own PostgreSQL, bound to a fixed port so it can be restarted'
        def port = freePort()
        def postgres = new PostgreSQLContainer('postgres:16-alpine')
                .withDatabaseName('ledger').withUsername('ledger').withPassword('ledger')
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig.withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(5432)))
                }
        postgres.start()
        def api = new ApiClient([DATABASE_URL: "jdbc:postgresql://localhost:${port}/ledger", DATABASE_CONNECT_TIMEOUT_MS: '1000', DATABASE_POOL_SIZE: '4'])
        def from = api.createAccount('USD', '100.00')
        def to = api.createAccount('USD')
        def key = "outage-${UUID.randomUUID()}".toString()

        when: 'PostgreSQL stops'
        postgres.dockerClient.stopContainerCmd(postgres.containerId).exec()
        def refused = api.transfer(key, from, to, '40.00')
        def health = api.get('/health')

        then: 'the transfer is refused with 503 and the health check says PostgreSQL is down'
        refused.status == 503
        refused.body.code == 'storage_unavailable'
        refused.header('Retry-After') == '1'
        health.status == 503
        health.body.postgres == 'down'

        when: 'PostgreSQL comes back'
        postgres.dockerClient.startContainerCmd(postgres.containerId).exec()
        awaitConnectable("jdbc:postgresql://localhost:${port}/ledger")
        def retried = api.transfer(key, from, to, '40.00')

        then: 'the failed attempt did not use up the key and the money moves exactly once'
        retried.status == 201
        api.get("/accounts/${from}").body.balance == '60.00'
        api.get("/accounts/${to}").body.balance == '40.00'
        api.transfer(key, from, to, '40.00').status == 200

        cleanup:
        api?.close()
        postgres?.stop()
    }

    private static int freePort() {
        new ServerSocket(0).withCloseable { it.localPort }
    }

    /** A readiness poll for the restarted server. It is not used to order events inside a test. */
    private static void awaitConnectable(String url) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (true) {
            try {
                DriverManager.getConnection(url, 'ledger', 'ledger').close()
                return
            } catch (java.sql.SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw e
                }
                Thread.sleep(200)
            }
        }
    }
}
