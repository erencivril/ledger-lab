package dev.erencivril.ledger.persistence;

import dev.erencivril.ledger.application.ApplicationException;
import dev.erencivril.ledger.application.Ports.AccountRepository;
import dev.erencivril.ledger.application.Ports.FxRateRepository;
import dev.erencivril.ledger.application.Ports.IdempotencyRepository;
import dev.erencivril.ledger.application.Ports.JournalRepository;
import dev.erencivril.ledger.application.Ports.LedgerTransaction;
import dev.erencivril.ledger.application.Ports.TransferRepository;
import dev.erencivril.ledger.application.Ports.UnitOfWork;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

/**
 * One call to {@link #execute} is one database transaction. jOOQ commits when the function returns
 * and rolls back when it throws; unchecked exceptions (domain and application errors) pass through
 * unchanged.
 */
public final class JooqUnitOfWork implements UnitOfWork {

    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";
    private static final String CONNECTION_EXCEPTION_CLASS = "08";
    private static final String ADMIN_SHUTDOWN = "57P01";

    private final DSLContext dsl;

    public JooqUnitOfWork(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public <T> T execute(Function<LedgerTransaction, T> work) {
        try {
            return dsl.transactionResult(configuration -> work.apply(new JooqLedgerTransaction(configuration.dsl())));
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    /**
     * Turns the two kinds of database failure the application can act on into application exceptions:
     * "a competing transaction won" (retry now) and "the database is not there" (retry later). Every other
     * database error is a bug or corruption and passes through unchanged.
     */
    private static RuntimeException translate(DataAccessException e) {
        String state = e.sqlState();
        if (SERIALIZATION_FAILURE.equals(state) || DEADLOCK_DETECTED.equals(state)) {
            return new ApplicationException.ConcurrentUpdate("PostgreSQL aborted the transaction (SQLSTATE " + state + ")");
        }
        if (isConnectionProblem(e, state)) {
            return new ApplicationException.StorageUnavailable(e);
        }
        return e;
    }

    private static boolean isConnectionProblem(DataAccessException e, String state) {
        if (state != null && (state.startsWith(CONNECTION_EXCEPTION_CLASS) || ADMIN_SHUTDOWN.equals(state))) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            // HikariCP throws this when it cannot hand out a connection within the timeout.
            if (cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException) {
                return true;
            }
        }
        return false;
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
