package dev.erencivril.ledger.domain;

public enum TransferKind {
    /** A customer moves money to another customer account. */
    TRANSFER,
    /** The treasury funds a new account. */
    OPENING_DEPOSIT
}
