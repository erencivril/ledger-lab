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
        def b = ledger.openAccount(USD)
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
        def before = ledger.sql.firstRow('select count(*) c from risk_outbox').c

        when:
        ledger.unitOfWork.execute { tx ->
            def a = ledger.openAccount(USD, '100.00')
            // Deliberately abort the open transaction. No transfer is committed by it.
            throw new IllegalStateException('abort')
        }

        then:
        thrown(IllegalStateException)
        ledger.sql.firstRow('select count(*) c from risk_outbox').c == before

        cleanup:
        ledger?.close()
    }

    def "two polling workers process disjoint locked batches and lose no payments"() {
        given:
        def ledger = new TestLedger()
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD)
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
        def b = ledger.openAccount(USD)
        def held = ledger.transfers.transfer(ledger.command(UUID.randomUUID().toString(), a, b, '150.00')).transfer()

        expect:
        ledger.sql.firstRow('select count(*) c from risk_outbox where transfer_id = ?', [held.id()]).c == 0

        when:
        ledger.holds.release(held.id())

        then:
        ledger.sql.firstRow('select count(*) c from risk_outbox where transfer_id = ?', [held.id()]).c == 1

        when:
        def otherPayee = ledger.openAccount(USD)
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
        def b = ledger.openAccount(USD)
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
}
