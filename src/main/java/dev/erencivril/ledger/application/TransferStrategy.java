package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.Ports.LedgerTransaction;
import dev.erencivril.ledger.domain.Money;
import java.util.UUID;

/**
 * How the balances of the two accounts of a transfer are changed safely when other transfers run at the
 * same time. There are three implementations, and the rest of the transfer (idempotency key, transfer
 * row, journal) is identical for all of them.
 *
 * <p>An implementation runs inside the caller's database transaction. It must either change both
 * balances exactly as the returned {@link BalanceChange} says, or throw. It never commits.
 */
public interface TransferStrategy {

    /**
     * @throws ApplicationException.AccountNotFound if an account does not exist
     * @throws dev.erencivril.ledger.domain.DomainException if a business rule refuses the transfer
     * @throws ApplicationException.ConcurrentUpdate if a competing transfer got in the way; the whole
     *     transaction can be run again
     */
    BalanceChange changeBalances(LedgerTransaction tx, UUID sourceId, UUID destinationId, Money amount);
}
