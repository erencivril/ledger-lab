package dev.erencivril.ledger.persistence;

import dev.erencivril.ledger.domain.AccountKind;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.LedgerAudit;
import dev.erencivril.ledger.domain.LedgerAudit.AccountRow;
import dev.erencivril.ledger.domain.LedgerAudit.EntryRow;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Reads every account and every journal entry and hands them to {@link LedgerAudit}. Used by the stress
 * tests and the benchmark, never by the request path: it loads the whole journal into memory.
 *
 * <p>Both reads happen in one REPEATABLE READ transaction, so they see the same snapshot even while
 * transfers are still being committed.
 */
public final class LedgerAuditReader {

    private LedgerAuditReader() {}

    public static List<String> violations(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (Statement statement = connection.createStatement()) {
                List<AccountRow> accounts = readAccounts(statement);
                List<EntryRow> entries = readEntries(statement);
                connection.commit();
                return LedgerAudit.violations(accounts, entries);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the ledger for the audit", e);
        }
    }

    private static List<AccountRow> readAccounts(Statement statement) throws SQLException {
        List<AccountRow> accounts = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("select id, kind, balance from accounts")) {
            while (rows.next()) {
                AccountKind kind = AccountKind.valueOf(rows.getString("kind"));
                // A treasury account has no meaningful stored balance, so none is passed on.
                BigDecimal stored = kind == AccountKind.CUSTOMER ? rows.getBigDecimal("balance") : null;
                accounts.add(new AccountRow(rows.getObject("id", UUID.class), kind, stored));
            }
        }
        return accounts;
    }

    private static List<EntryRow> readEntries(Statement statement) throws SQLException {
        List<EntryRow> entries = new ArrayList<>();
        String sql = "select id, transfer_id, account_id, currency, amount, balance_after from journal_entries";
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                entries.add(new EntryRow(
                        rows.getLong("id"),
                        rows.getObject("transfer_id", UUID.class),
                        rows.getObject("account_id", UUID.class),
                        Currency.parse(rows.getString("currency")),
                        rows.getBigDecimal("amount"),
                        rows.getBigDecimal("balance_after")));
            }
        }
        return entries;
    }
}
