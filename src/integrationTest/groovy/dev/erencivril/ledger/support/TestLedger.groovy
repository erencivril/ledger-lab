package dev.erencivril.ledger.support

import com.zaxxer.hikari.HikariDataSource
import dev.erencivril.ledger.application.AccountService
import dev.erencivril.ledger.application.HoldService
import dev.erencivril.ledger.application.LedgerRiskAssessor
import dev.erencivril.ledger.application.RiskAssessor
import dev.erencivril.ledger.domain.HoldAccounts
import dev.erencivril.ledger.risk.RuleSetLoader
import dev.erencivril.ledger.risk.RuleSetYaml
import dev.erencivril.ledger.application.LockStrategyKind
import dev.erencivril.ledger.application.Ports
import dev.erencivril.ledger.application.RetryPolicy
import dev.erencivril.ledger.application.TransferService
import dev.erencivril.ledger.domain.Currency
import dev.erencivril.ledger.domain.FxRateTable
import dev.erencivril.ledger.domain.Money
import dev.erencivril.ledger.domain.TransferPlanner
import dev.erencivril.ledger.domain.TreasuryAccounts
import dev.erencivril.ledger.persistence.Database
import dev.erencivril.ledger.persistence.JooqUnitOfWork
import dev.erencivril.ledger.persistence.LedgerAuditReader
import groovy.sql.Sql

import java.time.Clock

/**
 * The real services wired against the Testcontainers Postgres, with the Redis parts pluggable and the
 * locking strategy chosen per instance. Retries wait for real but only a millisecond or so.
 */
class TestLedger implements Closeable {

    HikariDataSource dataSource
    Ports.UnitOfWork unitOfWork
    TransferPlanner planner
    AccountService accounts
    TransferService transfers
    HoldService holds
    Sql sql
    private final Ports.VelocityLimiter limiter
    private final Ports.IdempotencyCache cache
    private final LockStrategyKind strategyKind
    private final RetryPolicy retryPolicy

    TestLedger(Ports.VelocityLimiter limiter = Ports.VelocityLimiter.unlimited(),
               Ports.IdempotencyCache cache = Ports.IdempotencyCache.none(),
               int poolSize = 16,
               LockStrategyKind strategy = LockStrategyKind.PESSIMISTIC,
               RetryPolicy retryPolicy = new RetryPolicy(50, 1),
               org.testcontainers.postgresql.PostgreSQLContainer postgres = Infra.POSTGRES) {
        dataSource = Database.connect(postgres.jdbcUrl, postgres.username, postgres.password, poolSize)
        Database.migrate(dataSource)
        unitOfWork = new JooqUnitOfWork(dataSource)
        this.limiter = limiter
        this.cache = cache
        this.strategyKind = strategy
        this.retryPolicy = retryPolicy
        planner = unitOfWork.execute { tx ->
            new TransferPlanner(new FxRateTable(tx.fxRates().findAll()), new TreasuryAccounts(tx.accounts().treasuryIds()),
                    new HoldAccounts(tx.accounts().holdIds()))
        }
        accounts = new AccountService(unitOfWork, planner, Clock.systemUTC())
        holds = new HoldService(unitOfWork, planner, Clock.systemUTC())
        transfers = buildTransfers(RiskAssessor.disabled())
        sql = new Sql(dataSource)
    }

    private TransferService buildTransfers(RiskAssessor risk) {
        new TransferService(unitOfWork, strategyKind.create(planner), limiter, cache, Clock.systemUTC(), retryPolicy,
                Ports.Pause.sleeping(), risk, holds)
    }

    /**
     * Turns the risk checks on with the rule set in the given YAML. It is stored in the database like a
     * bundled one. A version number is only ever used with one text, because published versions are immutable.
     */
    void enableRisk(String yaml) {
        def parsed = RuleSetYaml.parse(yaml)
        def loader = new RuleSetLoader(unitOfWork)
        loader.publish(parsed, 'test')
        def engine = loader.engineFor(parsed.ruleSet().version())
        def rates = unitOfWork.execute { tx -> new FxRateTable(tx.fxRates().findAll()) }
        transfers = buildTransfers(new LedgerRiskAssessor(unitOfWork, engine, rates, Clock.systemUTC()))
    }

    def holdBalance(Currency currency) {
        sql.firstRow("select coalesce(sum(e.amount), 0) total from journal_entries e join accounts a on a.id = e.account_id where a.kind = 'HOLD' and a.currency = ?", [currency.name()]).total
    }

    def openAccount(Currency currency, String balance, String owner = 'test') {
        accounts.open(owner, currency, balance == null ? null : new Money(new BigDecimal(balance), currency))
    }

    TransferService.TransferCommand command(String key, def from, def to, String amount, Currency currency = Currency.USD) {
        new TransferService.TransferCommand(key, from.id(), to.id(), new Money(new BigDecimal(amount), currency))
    }

    BigDecimal balanceOf(UUID id) {
        sql.firstRow('select balance from accounts where id = ?', [id]).balance
    }

    /**
     * The ledger invariants over everything in the database, in two independent ways: LedgerAudit
     * replays the journal in Java, and the SQL below recomputes the same facts with GROUP BY.
     */
    void assertLedgerConsistent() {
        def problems = LedgerAuditReader.violations(dataSource)
        assert problems.isEmpty(): "ledger audit found: ${problems.take(10)}"

        def unbalanced = sql.rows('''
            select transfer_id, currency, sum(amount) total from journal_entries
            group by transfer_id, currency having sum(amount) <> 0''')
        assert unbalanced.isEmpty(): "unbalanced journals: $unbalanced"

        def drift = sql.rows('''
            select a.id, a.balance, coalesce(sum(e.amount), 0) entries
            from accounts a left join journal_entries e on e.account_id = a.id
            where a.kind = 'CUSTOMER' group by a.id, a.balance
            having a.balance <> coalesce(sum(e.amount), 0)''')
        assert drift.isEmpty(): "balances that differ from their journal: $drift"

        def negative = sql.rows("select id, balance from accounts where kind = 'CUSTOMER' and balance < 0")
        assert negative.isEmpty(): "negative balances: $negative"

        def perCurrency = sql.rows('select currency, sum(amount) total from journal_entries group by currency having sum(amount) <> 0')
        assert perCurrency.isEmpty(): "currencies that do not net to zero: $perCurrency"
    }

    @Override
    void close() {
        sql.close()
        dataSource.close()
    }
}
