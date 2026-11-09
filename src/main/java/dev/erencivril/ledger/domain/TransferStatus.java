package dev.erencivril.ledger.domain;

/**
 * Where a transfer is in its life. Almost every transfer is created COMPLETED. A transfer that the risk
 * engine holds starts PENDING and ends either COMPLETED (released) or REJECTED. Nothing leaves those two.
 */
public enum TransferStatus {
    /** Money has been taken from the sender and is waiting in a hold account. */
    PENDING,
    /** The destination has been paid. */
    COMPLETED,
    /** A held transfer was cancelled and the sender was refunded. */
    REJECTED
}
