package dev.erencivril.ledger.domain;

import java.math.BigDecimal;

/**
 * The outcome of planning a transfer, before anything is written.
 *
 * @param fxRate the rate applied, or {@code null} when both accounts use the same currency
 */
public record TransferPlan(
        Money sourceAmount,
        Money destinationAmount,
        BigDecimal fxRate,
        Journal journal,
        Money sourceBalanceAfter,
        Money destinationBalanceAfter) {}
