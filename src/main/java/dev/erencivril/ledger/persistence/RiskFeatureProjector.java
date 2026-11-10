package dev.erencivril.ledger.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A polling, at-least-once outbox consumer. All feature writes and acknowledgements commit together. */
public final class RiskFeatureProjector implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RiskFeatureProjector.class);
    private final DataSource dataSource;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "risk-feature-poller");
        thread.setDaemon(true);
        return thread;
    });
    public RiskFeatureProjector(DataSource dataSource) { this.dataSource = dataSource; }

    public void start() {
        worker.scheduleWithFixedDelay(() -> {
            try { poll(100); }
            catch (RuntimeException error) { log.warn("Risk feature projection failed; unacknowledged events will be retried", error); }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }

    /** Several instances can poll at once: each locks only its own batch and skips rows another has locked. */
    public int poll(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize must be 1..1000");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int processed = projectBatch(connection, batchSize);
                connection.commit();
                return processed;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not project risk features", error);
        }
    }

    private static int projectBatch(Connection connection, int batchSize) throws SQLException {
        String select = "SELECT * FROM risk_outbox WHERE processed_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED";
        try (PreparedStatement pending = connection.prepareStatement(select)) {
            pending.setInt(1, batchSize);
            int processed = 0;
            try (ResultSet row = pending.executeQuery()) {
                while (row.next()) {
                    project(connection, row);
                    try (PreparedStatement acknowledge = connection.prepareStatement(
                            "UPDATE risk_outbox SET processed_at = now() WHERE id = ?")) {
                        acknowledge.setLong(1, row.getLong("id"));
                        acknowledge.executeUpdate();
                    }
                    processed++;
                }
            }
            return processed;
        }
    }

    private static void project(Connection connection, ResultSet row) throws SQLException {
        String insert = "INSERT INTO risk_features_payments(transfer_id, sender_id, payee_id, amount, currency, amount_usd, completed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
        try (PreparedStatement payment = connection.prepareStatement(insert)) {
            payment.setObject(1, row.getObject("transfer_id"));
            payment.setObject(2, row.getObject("sender_id"));
            payment.setObject(3, row.getObject("payee_id"));
            payment.setBigDecimal(4, row.getBigDecimal("amount"));
            payment.setString(5, row.getString("currency"));
            payment.setBigDecimal(6, row.getBigDecimal("amount_usd"));
            payment.setObject(7, row.getObject("completed_at"));
            if (payment.executeUpdate() == 0) return; // redelivery must not add to aggregates a second time
        }
        try (PreparedStatement sender = connection.prepareStatement("""
                INSERT INTO risk_features_sender(sender_id, payment_count, total_usd, last_payment_at)
                VALUES (?, 1, ?, ?) ON CONFLICT(sender_id) DO UPDATE SET
                    payment_count = risk_features_sender.payment_count + 1,
                    total_usd = risk_features_sender.total_usd + excluded.total_usd,
                    last_payment_at = greatest(risk_features_sender.last_payment_at, excluded.last_payment_at)
                """)) {
            sender.setObject(1, row.getObject("sender_id"));
            sender.setBigDecimal(2, row.getBigDecimal("amount_usd"));
            sender.setObject(3, row.getObject("completed_at"));
            sender.executeUpdate();
        }
        try (PreparedStatement payee = connection.prepareStatement("""
                INSERT INTO risk_features_payee(sender_id, payee_id, payment_count, last_payment_at)
                VALUES (?, ?, 1, ?) ON CONFLICT(sender_id, payee_id) DO UPDATE SET
                    payment_count = risk_features_payee.payment_count + 1,
                    last_payment_at = greatest(risk_features_payee.last_payment_at, excluded.last_payment_at)
                """)) {
            payee.setObject(1, row.getObject("sender_id"));
            payee.setObject(2, row.getObject("payee_id"));
            payee.setObject(3, row.getObject("completed_at"));
            payee.executeUpdate();
        }
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException error) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
