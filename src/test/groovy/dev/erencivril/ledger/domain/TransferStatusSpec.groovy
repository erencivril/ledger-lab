package dev.erencivril.ledger.domain

import spock.lang.Specification

import java.time.Instant

import static dev.erencivril.ledger.domain.Currency.USD

class TransferStatusSpec extends Specification {

    static Money usd(String amount) { new Money(new BigDecimal(amount), USD) }

    static Transfer transfer(TransferStatus status) {
        new Transfer(UUID.randomUUID(), TransferKind.TRANSFER, UUID.randomUUID(), UUID.randomUUID(),
                usd('10.00'), usd('10.00'), null, Instant.EPOCH, status)
    }

    def "a transfer built without a status is COMPLETED, as every transfer was before holds existed"() {
        expect:
        new Transfer(UUID.randomUUID(), TransferKind.TRANSFER, UUID.randomUUID(), UUID.randomUUID(),
                usd('10.00'), usd('10.00'), null, Instant.EPOCH).status() == TransferStatus.COMPLETED
    }

    def "a pending transfer can be released and keeps everything else"() {
        given:
        def pending = transfer(TransferStatus.PENDING)

        when:
        def released = pending.released()

        then:
        released.status() == TransferStatus.COMPLETED
        released.id() == pending.id()
        released.sourceAmount() == pending.sourceAmount()
        released.createdAt() == pending.createdAt()
    }

    def "a pending transfer can be rejected"() {
        expect:
        transfer(TransferStatus.PENDING).rejected().status() == TransferStatus.REJECTED
    }

    def "only a pending transfer can be decided: #status"() {
        when:
        transfer(status).released()

        then:
        def e = thrown(DomainException.TransferNotPending)
        e.code() == 'transfer_not_pending'
        e.message.contains(status.name())

        when:
        transfer(status).rejected()

        then:
        thrown(DomainException.TransferNotPending)

        where:
        status << [TransferStatus.COMPLETED, TransferStatus.REJECTED]
    }
}
