package dev.erencivril.ledger;

import com.zaxxer.hikari.HikariDataSource;
import dev.erencivril.ledger.application.AccountService;
import dev.erencivril.ledger.application.TransferService;
import dev.erencivril.ledger.cache.RedisClients;
import dev.erencivril.ledger.cache.RedisHealthProbe;
import dev.erencivril.ledger.cache.RedisIdempotencyCache;
import dev.erencivril.ledger.cache.RedisVelocityLimiter;
import dev.erencivril.ledger.domain.FxRateTable;
import dev.erencivril.ledger.domain.TransferPlanner;
import dev.erencivril.ledger.domain.TreasuryAccounts;
import dev.erencivril.ledger.http.LedgerRoutes;
import dev.erencivril.ledger.persistence.Database;
import dev.erencivril.ledger.persistence.JooqUnitOfWork;
import dev.erencivril.ledger.persistence.PostgresHealthProbe;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import spark.Service;

/** Composition root: builds every object once and connects them. */
public final class App implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private final HikariDataSource dataSource;
    private final JedisPooled redis;
    private final Service http;

    private App(HikariDataSource dataSource, JedisPooled redis, Service http) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.http = http;
    }

    public static App start(Config config) {
        HikariDataSource dataSource = Database.connect(
                config.databaseUrl(), config.databaseUser(), config.databasePassword(), config.databasePoolSize());
        Database.migrate(dataSource);
        JedisPooled redis = RedisClients.connect(config.redisUrl());

        JooqUnitOfWork unitOfWork = new JooqUnitOfWork(dataSource);
        TransferPlanner planner = unitOfWork.execute(tx ->
                new TransferPlanner(new FxRateTable(tx.fxRates().findAll()), new TreasuryAccounts(tx.accounts().treasuryIds())));
        Clock clock = Clock.systemUTC();

        AccountService accounts = new AccountService(unitOfWork, planner, clock);
        TransferService transfers = new TransferService(
                unitOfWork,
                planner,
                new RedisVelocityLimiter(
                        redis, config.velocityMaxTransfers(), config.velocityWindowSeconds(), config.velocityFailOpen()),
                new RedisIdempotencyCache(redis),
                clock);

        Service http = Service.ignite().port(config.port()).threadPool(32, 4, 30_000);
        new LedgerRoutes(
                        accounts,
                        transfers,
                        new PostgresHealthProbe(dataSource),
                        new RedisHealthProbe(redis))
                .register(http);
        http.awaitInitialization();
        log.info(
                "ledger-lab listening on port {} (velocity limit {} transfers per {}s, fail-open={})",
                http.port(),
                config.velocityMaxTransfers(),
                config.velocityWindowSeconds(),
                config.velocityFailOpen());
        return new App(dataSource, redis, http);
    }

    public int port() {
        return http.port();
    }

    @Override
    public void close() {
        http.stop();
        http.awaitStop();
        redis.close();
        dataSource.close();
    }
}
