package dev.erencivril.ledger.domain;

import java.math.BigDecimal;

/**
 * The outcome of planning a hold: what the destination will get when the transfer is released (frozen now),
 * the journal that moves the money from the sender to the hold account, and the sender's new balance.
 */
public record HoldPlan(
        Money sourceAmount, Money destinationAmount, BigDecimal fxRate, Journal journal, Money sourceBalanceAfter) {}
