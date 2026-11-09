package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.ApplicationException.AccountNotFound;
import dev.erencivril.ledger.application.ApplicationException.TransferNotFound;
import dev.erencivril.ledger.application.Ports.LedgerTransaction;
import dev.erencivril.ledger.application.Ports.NewJournalEntry;
import dev.erencivril.ledger.application.Ports.UnitOfWork;
import dev.erencivril.ledger.application.TransferService.TransferCommand;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.HoldPlan;
import dev.erencivril.ledger.domain.ReleasePlan;
import dev.erencivril.ledger.domain.Transfer;
import dev.erencivril.ledger.domain.TransferKind;
import dev.erencivril.ledger.domain.TransferPlanner;
import dev.erencivril.ledger.domain.TransferStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The hold pattern. A held transfer happens in three steps, each in its own database transaction:
 *
 * <ol>
 *   <li>{@link #place}: the sender is debited and the money is credited to the hold account of that
 *       currency. The transfer row is PENDING. The destination has not been touched.
 *   <li>{@link #release}: the hold account pays the destination and the transfer becomes COMPLETED.
 *   <li>{@link #reject}: the hold account pays the sender back and the transfer becomes REJECTED.
 * </ol>
 *
 * Every step is a balanced journal, so the ledger audit passes at every moment, including while a transfer
 * is waiting. A crash between step 1 and step 2 or 3 loses nothing: the money is in the hold account and
 * the PENDING row says why.
 *
 * <p>Holds always lock the one customer account they change with {@code SELECT ... FOR UPDATE}, whichever
 * lock strategy is configured for ordinary transfers. They are rare, and one lock on one row cannot
 * deadlock. Release and reject first lock the transfer row, so two people deciding on the same transfer
 * take turns, and the second one finds it no longer PENDING.
 */
public final class HoldService {

    private final UnitOfWork unitOfWork;
    private final TransferPlanner planner;
    private final Clock clock;

    public HoldService(UnitOfWork unitOfWork, TransferPlanner planner, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.planner = planner;
        this.clock = clock;
    }

    /** Step 1, inside the caller's transaction: debit the sender into the hold account. */
    Posting place(LedgerTransaction tx, TransferCommand command, Instant now) {
        Account source = tx.accounts()
                .findForUpdate(command.sourceAccountId())
                .orElseThrow(() -> new AccountNotFound(command.sourceAccountId()));
        Account destination = tx.accounts()
                .find(command.destinationAccountId())
                .orElseThrow(() -> new AccountNotFound(command.destinationAccountId()));

        HoldPlan plan = planner.planHold(source, destination, command.amount());
        tx.accounts().updateBalance(source.id(), plan.sourceBalanceAfter());

        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                TransferKind.TRANSFER,
                source.id(),
                destination.id(),
                plan.sourceAmount(),
                plan.destinationAmount(),
                plan.fxRate(),
                now,
                TransferStatus.PENDING);
        List<NewJournalEntry> entries = plan.journal().lines().stream()
                .map(line -> new NewJournalEntry(
                        line.accountId(),
                        line.amount(),
                        line.accountId().equals(source.id()) ? plan.sourceBalanceAfter() : null))
                .toList();
        return new Posting(transfer, entries);
    }

    /** Step 2: pay the destination. Fails with {@code transfer_not_pending} if somebody decided first. */
    public Transfer release(UUID transferId) {
        return unitOfWork.execute(tx -> {
            Transfer pending = lockPending(tx, transferId);
            Transfer completed = pending.released(); // throws if it is not PENDING, before any account is locked
            Account destination = tx.accounts()
                    .findForUpdate(pending.destinationAccountId())
                    .orElseThrow(() -> new AccountNotFound(pending.destinationAccountId()));
            ReleasePlan plan = planner.planRelease(pending, destination);
            return finish(tx, completed, plan, destination.id());
        });
    }

    /** Step 3 (the other way): refund the sender. Fails with {@code transfer_not_pending} if somebody decided first. */
    public Transfer reject(UUID transferId) {
        return unitOfWork.execute(tx -> {
            Transfer pending = lockPending(tx, transferId);
            Transfer refunded = pending.rejected(); // throws if it is not PENDING, before any account is locked
            Account source = tx.accounts()
                    .findForUpdate(pending.sourceAccountId())
                    .orElseThrow(() -> new AccountNotFound(pending.sourceAccountId()));
            ReleasePlan plan = planner.planReject(pending, source);
            return finish(tx, refunded, plan, source.id());
        });
    }

    private static Transfer lockPending(LedgerTransaction tx, UUID transferId) {
        return tx.transfers().findForUpdate(transferId).orElseThrow(() -> new TransferNotFound(transferId));
    }

    /** Writes the journal and the new status. {@code paidAccountId} is the one customer account in the journal. */
    private Transfer finish(LedgerTransaction tx, Transfer resolved, ReleasePlan plan, UUID paidAccountId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        tx.accounts().updateBalance(paidAccountId, plan.paidAccountBalanceAfter());
        List<NewJournalEntry> entries = plan.journal().lines().stream()
                .map(line -> new NewJournalEntry(
                        line.accountId(),
                        line.amount(),
                        line.accountId().equals(paidAccountId) ? plan.paidAccountBalanceAfter() : null))
                .toList();
        tx.journal().append(resolved.id(), entries, now);
        tx.transfers().resolve(resolved.id(), resolved.status(), now);
        return resolved;
    }
}
