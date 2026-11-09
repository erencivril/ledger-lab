package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.ApplicationException.ConcurrentUpdate;
import dev.erencivril.ledger.application.Ports.LedgerTransaction;
import dev.erencivril.ledger.application.Ports.VersionedAccount;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.TransferPlan;
import dev.erencivril.ledger.domain.TransferPlanner;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Read without locking, write only if nobody else wrote in between.
 *
 * <ol>
 *   <li>Plain SELECT of both accounts, remembering each account's {@code version}.
 *   <li>Decide in memory.
 *   <li>{@code UPDATE ... SET balance = ?, version = version + 1 WHERE id = ? AND version = ?}, in ascending
 *       id order. If it changes no row, somebody else wrote first, so this attempt is thrown away.
 * </ol>
 *
 * A thrown-away attempt is a {@link ConcurrentUpdate}. The transaction rolls back and {@link TransferService}
 * runs the whole thing again with fresh reads. This is cheap when conflicts are rare, because nobody waits.
 * It gets expensive when many transfers hit the same account: most attempts lose and most work is repeated.
 */
public final class OptimisticVersionStrategy implements TransferStrategy {

    private final TransferPlanner planner;

    public OptimisticVersionStrategy(TransferPlanner planner) {
        this.planner = planner;
    }

    @Override
    public BalanceChange changeBalances(LedgerTransaction tx, UUID sourceId, UUID destinationId, Money amount) {
        Map<UUID, VersionedAccount> read = new HashMap<>();
        for (UUID id : AccountOrder.ascending(sourceId, destinationId)) {
            tx.accounts().findVersioned(id).ifPresent(versioned -> read.put(id, versioned));
        }
        VersionedAccount source = requireFound(read, sourceId);
        VersionedAccount destination = requireFound(read, destinationId);

        TransferPlan plan = planner.plan(source.account(), destination.account(), amount);

        for (UUID id : AccountOrder.ascending(sourceId, destinationId)) {
            Money newBalance = id.equals(sourceId) ? plan.sourceBalanceAfter() : plan.destinationBalanceAfter();
            boolean written = tx.accounts().updateBalanceIfVersion(id, newBalance, read.get(id).version());
            if (!written) {
                throw new ConcurrentUpdate("Account " + id + " was changed by another transfer");
            }
        }
        return new BalanceChange(plan, plan.sourceBalanceAfter(), plan.destinationBalanceAfter());
    }

    private static VersionedAccount requireFound(Map<UUID, VersionedAccount> read, UUID id) {
        VersionedAccount versioned = read.get(id);
        if (versioned == null) {
            throw new ApplicationException.AccountNotFound(id);
        }
        return versioned;
    }
}
