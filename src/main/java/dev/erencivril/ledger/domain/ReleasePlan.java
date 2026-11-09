package dev.erencivril.ledger.domain;

/**
 * The journal that finishes a held transfer, and the new balance of the one account that is paid: the
 * destination for a release, the sender for a reject.
 */
public record ReleasePlan(Journal journal, Money paidAccountBalanceAfter) {}
