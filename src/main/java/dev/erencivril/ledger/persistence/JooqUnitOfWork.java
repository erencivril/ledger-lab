package dev.erencivril.ledger.persistence;

import dev.erencivril.ledger.application.Ports.AccountRepository;
import dev.erencivril.ledger.application.Ports.FxRateRepository;
import dev.erencivril.ledger.application.Ports.IdempotencyRepository;
import dev.erencivril.ledger.application.Ports.JournalRepository;
import dev.erencivril.ledger.application.Ports.LedgerTransaction;
import dev.erencivril.ledger.application.Ports.TransferRepository;
import dev.erencivril.ledger.application.Ports.UnitOfWork;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * One call to {@link #execute} is one database transaction. jOOQ commits when the function returns
 * and rolls back when it throws; unchecked exceptions (domain and application errors) pass through
 * unchanged.
 */
public final class JooqUnitOfWork implements UnitOfWork {

    private final DSLContext dsl;

    public JooqUnitOfWork(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public <T> T execute(Function<LedgerTransaction, T> work) {
        return dsl.transactionResult(configuration -> work.apply(new JooqLedgerTransaction(configuration.dsl())));
    }

    private record JooqLedgerTransaction(
            AccountRepository accounts,
            TransferRepository transfers,
            JournalRepository journal,
            IdempotencyRepository idempotency,
            FxRateRepository fxRates)
            implements LedgerTransaction {

        JooqLedgerTransaction(DSLContext tx) {
            this(
                    new JooqAccountRepository(tx),
                    new JooqTransferRepository(tx),
                    new JooqJournalRepository(tx),
                    new JooqIdempotencyRepository(tx),
                    new JooqFxRateRepository(tx));
        }
    }
}
