package dev.erencivril.ledger;

import com.zaxxer.hikari.HikariDataSource;
import dev.erencivril.ledger.application.AccountService;
import dev.erencivril.ledger.application.HoldService;
import dev.erencivril.ledger.application.LedgerRiskAssessor;
import dev.erencivril.ledger.application.Ports.Pause;
import dev.erencivril.ledger.application.RetryPolicy;
import dev.erencivril.ledger.application.RiskAssessor;
import dev.erencivril.ledger.application.TransferService;
import dev.erencivril.ledger.cache.RedisClients;
import dev.erencivril.ledger.cache.RedisHealthProbe;
import dev.erencivril.ledger.cache.RedisIdempotencyCache;
import dev.erencivril.ledger.cache.RedisVelocityLimiter;
import dev.erencivril.ledger.cache.RedisRiskVelocity;
import dev.erencivril.ledger.domain.FxRateTable;
import dev.erencivril.ledger.domain.HoldAccounts;
import dev.erencivril.ledger.domain.TransferPlanner;
import dev.erencivril.ledger.domain.TreasuryAccounts;
import dev.erencivril.ledger.http.LedgerRoutes;
import dev.erencivril.ledger.persistence.Database;
import dev.erencivril.ledger.persistence.JooqUnitOfWork;
import dev.erencivril.ledger.persistence.PostgresHealthProbe;
import dev.erencivril.ledger.persistence.RiskFeatureProjector;
import dev.erencivril.ledger.risk.RuleSetLoader;
import java.time.Clock;
import java.util.List;
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
    private final RiskFeatureProjector projector;

    private App(HikariDataSource dataSource, JedisPooled redis, Service http, RiskFeatureProjector projector) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.http = http;
        this.projector = projector;
    }

    public static App start(Config config) {
        HikariDataSource dataSource = Database.connect(
                config.databaseUrl(), config.databaseUser(),
                config.databasePassword(),
                config.databasePoolSize(),
                config.databaseConnectTimeoutMillis());
        Database.migrate(dataSource);
        JedisPooled redis = RedisClients.connect(config.redisUrl());

        JooqUnitOfWork unitOfWork = new JooqUnitOfWork(dataSource);
        TransferPlanner planner = unitOfWork.execute(tx ->
                new TransferPlanner(
                        new FxRateTable(tx.fxRates().findAll()),
                        new TreasuryAccounts(tx.accounts().treasuryIds()),
                        new HoldAccounts(tx.accounts().holdIds())));
        Clock clock = Clock.systemUTC();
        FxRateTable rates = unitOfWork.execute(tx -> new FxRateTable(tx.fxRates().findAll()));

        RiskAssessor risk = riskAssessor(config, unitOfWork, rates, clock, redis);
        HoldService holds = new HoldService(unitOfWork, planner, clock);

        AccountService accounts = new AccountService(unitOfWork, planner, clock);
        TransferService transfers = new TransferService(
                unitOfWork,
                config.lockStrategy().create(planner),
                new RedisVelocityLimiter(
                        redis, config.velocityMaxTransfers(), config.velocityWindowSeconds(), config.velocityFailOpen()),
                new RedisIdempotencyCache(redis),
                clock,
                new RetryPolicy(config.transferMaxAttempts(), config.transferRetryBaseMillis()),
                Pause.sleeping(),
                risk,
                holds);

        Service http = Service.ignite().port(config.port()).threadPool(32, 4, 30_000);
        new LedgerRoutes(
                        accounts,
                        transfers,
                        holds,
                        new PostgresHealthProbe(dataSource),
                        new RedisHealthProbe(redis))
                .register(http);
        http.awaitInitialization();
        log.info(
                "ledger-lab listening on port {} (lock strategy {}, velocity limit {} transfers per {}s, fail-open={})",
                http.port(),
                config.lockStrategy().name().toLowerCase(),
                config.velocityMaxTransfers(),
                config.velocityWindowSeconds(),
                config.velocityFailOpen());
        RiskFeatureProjector projector = new RiskFeatureProjector(dataSource);
        projector.start();
        return new App(dataSource, redis, http, projector);
    }

    /** Loads the bundled rule sets into the database and builds the engine for the configured version. */
    private static RiskAssessor riskAssessor(Config config, JooqUnitOfWork unitOfWork, FxRateTable rates, Clock clock, JedisPooled redis) {
        if (!config.riskEnabled()) {
            return RiskAssessor.disabled();
        }
        RuleSetLoader loader = new RuleSetLoader(unitOfWork);
        List<Integer> versions = loader.loadBundled();
        int version = config.riskRuleSetVersion() != null
                ? config.riskRuleSetVersion()
                : versions.get(versions.size() - 1);
        return new LedgerRiskAssessor(unitOfWork, loader.engineFor(version), rates, clock, new RedisRiskVelocity(redis));
    }

    public int port() {
        return http.port();
    }

    @Override
    public void close() {
        http.stop();
        http.awaitStop();
        projector.close();
        redis.close();
        dataSource.close();
    }
}
