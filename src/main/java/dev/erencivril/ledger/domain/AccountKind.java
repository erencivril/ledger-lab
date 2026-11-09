package dev.erencivril.ledger.domain;

/**
 * CUSTOMER accounts hold user money and can never go negative. SYSTEM (treasury) accounts are the
 * counterparty for deposits and currency conversion, so their balance is the negative of what
 * customers hold. HOLD accounts (one per currency) keep the money of transfers that are waiting for a
 * decision; like the treasury they have no stored balance, it is the sum of their journal entries.
 */
public enum AccountKind {
    CUSTOMER,
    SYSTEM,
    HOLD
}
