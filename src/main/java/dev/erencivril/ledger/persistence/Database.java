package dev.erencivril.ledger.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

/** Connection pool and schema migration. */
public final class Database {

    private Database() {}

    public static HikariDataSource connect(String jdbcUrl, String user, String password, int poolSize) {
        return connect(jdbcUrl, user, password, poolSize, 10_000);
    }

    /** @param connectTimeoutMillis how long a request waits for a free connection before it fails */
    public static HikariDataSource connect(
            String jdbcUrl, String user, String password, int poolSize, long connectTimeoutMillis) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("ledger");
        config.setConnectionTimeout(connectTimeoutMillis);
        return new HikariDataSource(config);
    }

    public static void migrate(HikariDataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
