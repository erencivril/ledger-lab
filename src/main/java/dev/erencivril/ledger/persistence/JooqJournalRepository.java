package dev.erencivril.ledger.persistence;

import static dev.erencivril.ledger.persistence.jooq.Tables.JOURNAL_ENTRIES;

import dev.erencivril.ledger.application.Ports.JournalRepository;
import dev.erencivril.ledger.application.Ports.NewJournalEntry;
import dev.erencivril.ledger.application.Ports.StatementEntry;
import dev.erencivril.ledger.application.Ports.StatementPage;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.Money;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

final class JooqJournalRepository implements JournalRepository {

    private final DSLContext dsl;

    JooqJournalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void append(UUID transferId, List<NewJournalEntry> entries, Instant createdAt) {
        // The journal entries' sum is checked by a deferred trigger at commit; see V3 migration.
        var insert = dsl.insertInto(
                JOURNAL_ENTRIES,
                JOURNAL_ENTRIES.TRANSFER_ID,
                JOURNAL_ENTRIES.ACCOUNT_ID,
                JOURNAL_ENTRIES.CURRENCY,
                JOURNAL_ENTRIES.AMOUNT,
                JOURNAL_ENTRIES.BALANCE_AFTER,
                JOURNAL_ENTRIES.CREATED_AT);
        for (NewJournalEntry entry : entries) {
            insert = insert.values(
                    transferId,
                    entry.accountId(),
                    entry.amount().currency().name(),
                    entry.amount().amount(),
                    entry.balanceAfter() == null ? null : entry.balanceAfter().amount(),
                    createdAt.atOffset(ZoneOffset.UTC));
        }
        insert.execute();
    }

    @Override
    public StatementPage statement(UUID accountId, int limit, Long before) {
        var condition = JOURNAL_ENTRIES.ACCOUNT_ID.eq(accountId);
        if (before != null) {
            condition = condition.and(JOURNAL_ENTRIES.ID.lt(before));
        }
        List<StatementEntry> rows = dsl.selectFrom(JOURNAL_ENTRIES)
                .where(condition)
                .orderBy(JOURNAL_ENTRIES.ID.desc())
                .limit(limit + 1)
                .fetch(row -> {
                    Currency currency = Currency.parse(row.getCurrency());
                    return new StatementEntry(
                            row.getId(),
                            row.getTransferId(),
                            new Money(row.getAmount(), currency),
                            row.getBalanceAfter() == null ? null : new Money(row.getBalanceAfter(), currency),
                            row.getCreatedAt().toInstant());
                });
        if (rows.size() <= limit) {
            return new StatementPage(rows, null);
        }
        List<StatementEntry> page = rows.subList(0, limit);
        return new StatementPage(page, page.get(limit - 1).id());
    }
}
