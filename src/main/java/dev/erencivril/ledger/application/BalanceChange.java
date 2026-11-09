package dev.erencivril.ledger.application;

import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.TransferPlan;

/**
 * What a strategy has just written. The two balances are the ones now stored in the database, so they go
 * on the journal entries. For the atomic strategy they come back from the UPDATE statements, not from the plan.
 */
public record BalanceChange(TransferPlan plan, Money sourceBalanceAfter, Money destinationBalanceAfter) {}
