package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.Ports.LedgerTransaction;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.TransferPlan;
import dev.erencivril.ledger.domain.TransferPlanner;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The default. Lock first, then read, then write.
 *
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE} on both accounts, in ascending id order. A competing transfer
 *       that wants one of these rows waits here until this transaction ends.
 *   <li>Read the balances (the SELECT returned them), decide, write both balances.
 * </ol>
 *
 * Nobody can change the rows between the read and the write, so no transfer ever has to be redone because of
 * a conflict. The price is waiting: transfers that share an account run one after the other.
 */
public final class PessimisticLockStrategy implements TransferStrategy {

    private final TransferPlanner planner;

    public PessimisticLockStrategy(TransferPlanner planner) {
        this.planner = planner;
    }

    @Override
    public BalanceChange changeBalances(LedgerTransaction tx, UUID sourceId, UUID destinationId, Money amount) {
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID id : AccountOrder.ascending(sourceId, destinationId)) {
            tx.accounts().findForUpdate(id).ifPresent(account -> locked.put(id, account));
        }
        Account source = AccountOrder.requireFound(locked, sourceId);
        Account destination = AccountOrder.requireFound(locked, destinationId);

        TransferPlan plan = planner.plan(source, destination, amount);

        tx.accounts().updateBalance(source.id(), plan.sourceBalanceAfter());
        tx.accounts().updateBalance(destination.id(), plan.destinationBalanceAfter());
        return new BalanceChange(plan, plan.sourceBalanceAfter(), plan.destinationBalanceAfter());
    }
}
