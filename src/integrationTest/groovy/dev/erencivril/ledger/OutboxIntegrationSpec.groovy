package dev.erencivril.ledger

import dev.erencivril.ledger.persistence.RiskFeatureProjector
import dev.erencivril.ledger.support.TestLedger
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

import static dev.erencivril.ledger.domain.Currency.USD

class OutboxIntegrationSpec extends Specification {

    def "a committed payment creates an outbox row and polling builds the risk read model once"() {
        given:
        def ledger = new TestLedger()
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, null)
        def transfer = ledger.transfers.transfer(ledger.command(UUID.randomUUID().toString(), a, b, '20.00')).transfer()
        def projector = new RiskFeatureProjector(ledger.dataSource)

        expect:
        ledger.sql.firstRow('select count(*) c from risk_outbox where transfer_id = ?', [transfer.id()]).c == 1

        when:
        while (projector.poll(100) > 0) { }

        then:
        ledger.sql.firstRow('select payment_count from risk_features_sender where sender_id = ?', [a.id()]).payment_count == 1
        ledger.sql.firstRow('select total_usd from risk_features_sender where sender_id = ?', [a.id()]).total_usd == 20G
        ledger.sql.firstRow('select payment_count from risk_features_payee where sender_id = ? and payee_id = ?', [a.id(), b.id()]).payment_count == 1

        when: 'redelivery after losing the consumer acknowledgement'
        ledger.sql.executeUpdate('update risk_outbox set processed_at = null where transfer_id = ?', [transfer.id()])
        while (projector.poll(100) > 0) { }

        then:
        ledger.sql.firstRow('select payment_count from risk_features_sender where sender_id = ?', [a.id()]).payment_count == 1

        cleanup:
        ledger?.close()
    }

    def "a rolled-back payment produces neither an event nor a feature"() {
        given:
        def ledger = new TestLedger()
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, null)
        def id = UUID.randomUUID()

        when:
        ledger.unitOfWork.execute { tx ->
            def transfer = new dev.erencivril.ledger.domain.Transfer(id, dev.erencivril.ledger.domain.TransferKind.TRANSFER,
                    a.id(), b.id(), new dev.erencivril.ledger.domain.Money(10G, USD),
                    new dev.erencivril.ledger.domain.Money(10G, USD), null, java.time.Instant.now())
            tx.transfers().insert(transfer)
            assert ledger.sql.firstRow('select count(*) c from risk_outbox where transfer_id = ?', [id]).c == 0 // invisible outside tx
            throw new IllegalStateException('abort')
        }

        then:
        thrown(IllegalStateException)
        ledger.sql.firstRow('select count(*) c from risk_outbox where transfer_id = ?', [id]).c == 0
        ledger.sql.firstRow('select count(*) c from transfers where id = ?', [id]).c == 0

        cleanup:
        ledger?.close()
    }

    def "two polling workers process disjoint locked batches and lose no payments"() {
        given:
        def ledger = new TestLedger()
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, null)
        (1..20).each { ledger.transfers.transfer(ledger.command(UUID.randomUUID().toString(), a, b, '2.00')) }
        def projector = new RiskFeatureProjector(ledger.dataSource)

        when:
        def workers = (1..2).collect { CompletableFuture.runAsync { while (projector.poll(3) > 0) { } } }
        workers*.join()
        while (projector.poll(100) > 0) { }

        then:
        ledger.sql.firstRow('select payment_count from risk_features_sender where sender_id = ?', [a.id()]).payment_count == 20
        ledger.sql.firstRow('select total_usd from risk_features_sender where sender_id = ?', [a.id()]).total_usd == 40G

        cleanup:
        ledger?.close()
    }

    def "a held payment emits nothing until released, and a rejected payment emits nothing"() {
        given:
        def ledger = new TestLedger()
        ledger.enableRisk(RiskHoldIntegrationSpec.RULES)
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, null)
        def held = ledger.transfers.transfer(ledger.command(UUID.randomUUID().toString(), a, b, '150.00')).transfer()

        expect:
        ledger.sql.firstRow('select count(*) c from risk_outbox where transfer_id = ?', [held.id()]).c == 0

        when:
        ledger.holds.release(held.id())

        then:
        ledger.sql.firstRow('select count(*) c from risk_outbox where transfer_id = ?', [held.id()]).c == 1

        when:
        def otherPayee = ledger.openAccount(USD, null)
        def rejected = ledger.transfers.transfer(ledger.command(UUID.randomUUID().toString(), a, otherPayee, '150.00')).transfer()
        ledger.holds.reject(rejected.id())

        then:
        ledger.sql.firstRow('select count(*) c from risk_outbox where transfer_id = ?', [rejected.id()]).c == 0

        cleanup:
        ledger?.close()
    }

    def "a stalled projection falls back to the ledger for risk facts; once caught up the facts agree"() {
        given:
        def ledger = new TestLedger()
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, null)
        ledger.transfers.transfer(ledger.command(UUID.randomUUID().toString(), a, b, '25.00'))

        when:
        def before = ledger.unitOfWork.execute { tx -> tx.risk().senderHistory(a.id(), java.time.Instant.EPOCH) }
        def projector = new RiskFeatureProjector(ledger.dataSource)
        while (projector.poll(100) > 0) { }
        def after = ledger.unitOfWork.execute { tx -> tx.risk().senderHistory(a.id(), java.time.Instant.EPOCH) }

        then:
        before.count() == 1
        after.count() == before.count()
        after.averageAmount() == before.averageAmount()
        ledger.unitOfWork.execute { tx -> tx.risk().payeeFacts(a.id(), b.id()).get().paidBefore() }

        cleanup:
        ledger?.close()
    }
    def "a projection failure rolls back deduplication, aggregates and acknowledgement together"() {
        given:
        def ledger = new TestLedger()
        def projector = new RiskFeatureProjector(ledger.dataSource)
        while (projector.poll(100) > 0) { }
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, null)
        def payment = ledger.transfers.transfer(ledger.command(UUID.randomUUID().toString(), a, b, '10.00')).transfer()
        // A temporary constraint makes the aggregate insert fail after the deduplication insert.
        ledger.sql.execute("alter table risk_features_sender add constraint projection_fault check (sender_id <> '${a.id()}') not valid".toString())

        when:
        projector.poll(100)

        then:
        thrown(IllegalStateException)
        ledger.sql.firstRow('select count(*) c from risk_features_payments where transfer_id = ?', [payment.id()]).c == 0
        ledger.sql.firstRow('select processed_at from risk_outbox where transfer_id = ?', [payment.id()]).processed_at == null

        when:
        ledger.sql.execute('alter table risk_features_sender drop constraint projection_fault')
        projector.poll(100)

        then:
        ledger.sql.firstRow('select payment_count from risk_features_sender where sender_id = ?', [a.id()]).payment_count == 1

        cleanup:
        ledger?.sql?.execute('alter table risk_features_sender drop constraint if exists projection_fault')
        projector?.close()
        ledger?.close()
    }

    def "a poller skips a locked pending row rather than waiting for it"() {
        given:
        def ledger = new TestLedger()
        def projector = new RiskFeatureProjector(ledger.dataSource)
        while (projector.poll(100) > 0) { }
        def a = ledger.openAccount(USD, '100.00')
        def b = ledger.openAccount(USD, null)
        def payment = ledger.transfers.transfer(ledger.command(UUID.randomUUID().toString(), a, b, '10.00')).transfer()
        def connection = ledger.dataSource.connection
        connection.autoCommit = false
        def statement = connection.prepareStatement('select id from risk_outbox where transfer_id = ? for update')
        statement.setObject(1, payment.id())
        statement.executeQuery().close()

        expect:
        projector.poll(100) == 0

        when:
        connection.rollback()

        then:
        projector.poll(100) == 1

        cleanup:
        statement?.close()
        connection?.close()
        projector?.close()
        ledger?.close()
    }
}
