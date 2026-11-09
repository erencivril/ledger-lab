package dev.erencivril.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A transfer. It is written in the same database transaction as its journal and balance changes, so it
 * exists in full or not at all. Most transfers are born COMPLETED; a held one is born PENDING and is
 * finished later by {@link #released()} or {@link #rejected()}.
 *
 * @param fxRate the rate applied, or {@code null} when no conversion happened
 */
public record Transfer(
        UUID id,
        TransferKind kind,
        UUID sourceAccountId,
        UUID destinationAccountId,
        Money sourceAmount,
        Money destinationAmount,
        BigDecimal fxRate,
        Instant createdAt,
        TransferStatus status) {

    /** A transfer that is already complete, which is what every transfer was before holds existed. */
    public Transfer(
            UUID id,
            TransferKind kind,
            UUID sourceAccountId,
            UUID destinationAccountId,
            Money sourceAmount,
            Money destinationAmount,
            BigDecimal fxRate,
            Instant createdAt) {
        this(id, kind, sourceAccountId, destinationAccountId, sourceAmount, destinationAmount, fxRate, createdAt,
                TransferStatus.COMPLETED);
    }

    /** The same transfer once the destination has been paid. Only a PENDING transfer can be released. */
    public Transfer released() {
        return resolvedAs(TransferStatus.COMPLETED);
    }

    /** The same transfer once the sender has been refunded. Only a PENDING transfer can be rejected. */
    public Transfer rejected() {
        return resolvedAs(TransferStatus.REJECTED);
    }

    private Transfer resolvedAs(TransferStatus newStatus) {
        if (status != TransferStatus.PENDING) {
            throw new DomainException.TransferNotPending(id, status);
        }
        return new Transfer(id, kind, sourceAccountId, destinationAccountId, sourceAmount, destinationAmount, fxRate,
                createdAt, newStatus);
    }
}
