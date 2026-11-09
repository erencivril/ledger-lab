package dev.erencivril.ledger.domain;

/**
 * CUSTOMER accounts hold user money and can never go negative. SYSTEM (treasury) accounts are the
 * counterparty for deposits and currency conversion, so their balance is the negative of what
 * customers hold.
 */
public enum AccountKind {
    CUSTOMER,
    SYSTEM
}
