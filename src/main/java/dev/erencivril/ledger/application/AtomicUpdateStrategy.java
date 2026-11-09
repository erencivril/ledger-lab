package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.Ports.LedgerTransaction;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.DomainException;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.TransferPlan;
import dev.erencivril.ledger.domain.TransferPlanner;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Let the database do the check and the arithmetic in one statement.
 *
 * <pre>
 *   UPDATE accounts SET balance = balance - ?   WHERE id = ? AND balance >= ?   -- the debit
 *   UPDATE accounts SET balance = balance + ?   WHERE id = ?                    -- the credit
 * </pre>
 *
 * There is no explicit lock and no version. The UPDATE itself locks the row for the rest of the
 * transaction, and the {@code balance >= ?} test is evaluated against the row as it is at that moment,
 * never against an older read. If the debit changes no row, there was not enough money.
 *
 * <p>The accounts are still read once, without locking, but only to find their currencies for the plan.
 * The balances in that read may be out of date and are never used. The balances stored on the journal
 * entries are the ones the UPDATE statements return.
 *
 * <p>The rows are updated in ascending id order, like in the other strategies, so two transfers cannot
 * deadlock. Never retries: a refused debit is a real "insufficient funds".
 */
public final class AtomicUpdateStrategy implements TransferStrategy {

    private final TransferPlanner planner;

    public AtomicUpdateStrategy(TransferPlanner planner) {
        this.planner = planner;
    }

    @Override
    public BalanceChange changeBalances(LedgerTransaction tx, UUID sourceId, UUID destinationId, Money amount) {
        Map<UUID, Account> read = new HashMap<>();
        for (UUID id : AccountOrder.ascending(sourceId, destinationId)) {
            tx.accounts().find(id).ifPresent(account -> read.put(id, account));
        }
        Account source = AccountOrder.requireFound(read, sourceId);
        Account destination = AccountOrder.requireFound(read, destinationId);

        // Every rule except "is there enough money". The UPDATE answers that one.
        TransferPlan plan = planner.planWithoutFundsCheck(source, destination, amount);

        Money sourceBalanceAfter = null;
        Money destinationBalanceAfter = null;
        for (UUID id : AccountOrder.ascending(sourceId, destinationId)) {
            if (id.equals(sourceId)) {
                sourceBalanceAfter = debit(tx, source, plan.sourceAmount());
            } else {
                destinationBalanceAfter = tx.accounts().credit(id, plan.destinationAmount());
            }
        }
        return new BalanceChange(plan, sourceBalanceAfter, destinationBalanceAfter);
    }

    private static Money debit(LedgerTransaction tx, Account source, Money amount) {
        Optional<Money> balanceAfter = tx.accounts().debitIfFunded(source.id(), amount);
        if (balanceAfter.isPresent()) {
            return balanceAfter.get();
        }
        // The UPDATE found too little money. Read the balance again so the message shows the current one.
        Money current = tx.accounts().find(source.id()).map(Account::balance).orElse(source.balance());
        throw new DomainException.InsufficientFunds(source.id(), current, amount);
    }
}
