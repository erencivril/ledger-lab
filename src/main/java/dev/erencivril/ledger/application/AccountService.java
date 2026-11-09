package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.Ports.NewJournalEntry;
import dev.erencivril.ledger.application.Ports.StatementPage;
import dev.erencivril.ledger.application.Ports.UnitOfWork;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.DomainException;
import dev.erencivril.ledger.domain.Journal;
import dev.erencivril.ledger.domain.JournalLine;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.Transfer;
import dev.erencivril.ledger.domain.TransferKind;
import dev.erencivril.ledger.domain.TransferPlanner;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

public final class AccountService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final UnitOfWork unitOfWork;
    private final TransferPlanner planner;
    private final Clock clock;

    public AccountService(UnitOfWork unitOfWork, TransferPlanner planner, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.planner = planner;
        this.clock = clock;
    }

    /**
     * Opens a customer account. A non-zero opening balance is funded by the treasury and recorded as a
     * normal journal, so even the first money in the system is double-entry.
     */
    public Account open(String owner, Currency currency, Money openingBalance) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Account account = Account.open(UUID.randomUUID(), owner, currency, now);
        if (openingBalance != null && openingBalance.currency() != currency) {
            throw new DomainException.CurrencyMismatch(
                    "Opening balance is in " + openingBalance.currency() + " but the account is in " + currency);
        }
        boolean funded = openingBalance != null && !openingBalance.isZero();
        Journal deposit = funded ? planner.openingDeposit(account.id(), openingBalance) : null;

        return unitOfWork.execute(tx -> {
            tx.accounts().insert(account);
            if (!funded) {
                return account;
            }
            JournalLine treasuryLine = deposit.lines().get(0);
            Transfer transfer = new Transfer(
                    UUID.randomUUID(),
                    TransferKind.OPENING_DEPOSIT,
                    treasuryLine.accountId(),
                    account.id(),
                    openingBalance,
                    openingBalance,
                    null,
                    now);
            tx.transfers().insert(transfer);
            tx.accounts().updateBalance(account.id(), openingBalance);
            tx.journal()
                    .append(
                            transfer.id(),
                            List.of(
                                    new NewJournalEntry(treasuryLine.accountId(), treasuryLine.amount(), null),
                                    new NewJournalEntry(account.id(), openingBalance, openingBalance)),
                            now);
            return account.credit(openingBalance);
        });
    }

    public Account get(UUID id) {
        return unitOfWork
                .execute(tx -> tx.accounts().find(id))
                .orElseThrow(() -> new ApplicationException.AccountNotFound(id));
    }

    public StatementPage statement(UUID accountId, Integer limit, Long before) {
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        return unitOfWork.execute(tx -> {
            tx.accounts()
                    .find(accountId)
                    .orElseThrow(() -> new ApplicationException.AccountNotFound(accountId));
            return tx.journal().statement(accountId, pageSize, before);
        });
    }
}
