package dev.erencivril.ledger.persistence;

import dev.erencivril.ledger.application.Ports.HealthProbe;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

public final class PostgresHealthProbe implements HealthProbe {

    private final DataSource dataSource;

    public PostgresHealthProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean isUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }
}
