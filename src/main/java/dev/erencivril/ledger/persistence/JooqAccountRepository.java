package dev.erencivril.ledger.persistence;

import static dev.erencivril.ledger.persistence.jooq.Tables.ACCOUNTS;

import dev.erencivril.ledger.application.Ports.AccountRepository;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.AccountKind;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.persistence.jooq.tables.records.AccountsRecord;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

final class JooqAccountRepository implements AccountRepository {

    private final DSLContext dsl;

    JooqAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Account account) {
        dsl.insertInto(ACCOUNTS)
                .set(ACCOUNTS.ID, account.id())
                .set(ACCOUNTS.OWNER, account.owner())
                .set(ACCOUNTS.CURRENCY, account.currency().name())
                .set(ACCOUNTS.KIND, account.kind().name())
                .set(ACCOUNTS.BALANCE, account.balance().amount())
                .set(ACCOUNTS.CREATED_AT, account.createdAt().atOffset(ZoneOffset.UTC))
                .execute();
    }

    @Override
    public Optional<Account> find(UUID id) {
        return dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(id))
                .and(ACCOUNTS.KIND.eq(AccountKind.CUSTOMER.name()))
                .fetchOptional(JooqAccountRepository::toAccount);
    }

    @Override
    public Optional<Account> findForUpdate(UUID id) {
        return dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(id))
                .and(ACCOUNTS.KIND.eq(AccountKind.CUSTOMER.name()))
                .forUpdate()
                .fetchOptional(JooqAccountRepository::toAccount);
    }

    @Override
    public void updateBalance(UUID id, Money balance) {
        int updated = dsl.update(ACCOUNTS)
                .set(ACCOUNTS.BALANCE, balance.amount())
                .where(ACCOUNTS.ID.eq(id))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("Expected to update one account, updated " + updated);
        }
    }

    @Override
    public Map<Currency, UUID> treasuryIds() {
        Map<Currency, UUID> ids = new EnumMap<>(Currency.class);
        dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.KIND.eq(AccountKind.SYSTEM.name()))
                .forEach(row -> ids.put(Currency.parse(row.getCurrency()), row.getId()));
        return ids;
    }

    private static Account toAccount(AccountsRecord row) {
        Currency currency = Currency.parse(row.getCurrency());
        return new Account(
                row.getId(),
                row.getOwner(),
                currency,
                AccountKind.valueOf(row.getKind()),
                new Money(row.getBalance(), currency),
                row.getCreatedAt().toInstant());
    }
}
