package dev.erencivril.ledger.support

import com.zaxxer.hikari.HikariDataSource
import dev.erencivril.ledger.application.AccountService
import dev.erencivril.ledger.application.Ports
import dev.erencivril.ledger.application.TransferService
import dev.erencivril.ledger.domain.Currency
import dev.erencivril.ledger.domain.FxRateTable
import dev.erencivril.ledger.domain.Money
import dev.erencivril.ledger.domain.TransferPlanner
import dev.erencivril.ledger.domain.TreasuryAccounts
import dev.erencivril.ledger.persistence.Database
import dev.erencivril.ledger.persistence.JooqUnitOfWork
import groovy.sql.Sql

import java.time.Clock

/** The real services wired against the Testcontainers Postgres, with the Redis parts pluggable. */
class TestLedger implements Closeable {

    HikariDataSource dataSource
    Ports.UnitOfWork unitOfWork
    AccountService accounts
    TransferService transfers
    Sql sql

    TestLedger(Ports.VelocityLimiter limiter = Ports.VelocityLimiter.unlimited(),
               Ports.IdempotencyCache cache = Ports.IdempotencyCache.none(),
               int poolSize = 16) {
        dataSource = Database.connect(Infra.jdbcUrl(), Infra.POSTGRES.username, Infra.POSTGRES.password, poolSize)
        Database.migrate(dataSource)
        unitOfWork = new JooqUnitOfWork(dataSource)
        def planner = unitOfWork.execute { tx ->
            new TransferPlanner(new FxRateTable(tx.fxRates().findAll()), new TreasuryAccounts(tx.accounts().treasuryIds()))
        }
        accounts = new AccountService(unitOfWork, planner, Clock.systemUTC())
        transfers = new TransferService(unitOfWork, planner, limiter, cache, Clock.systemUTC())
        sql = new Sql(dataSource)
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
     * The ledger invariants, checked directly in SQL over everything in the database:
     * every transfer balances per currency, every customer balance equals the sum of its entries,
     * and no customer balance is negative.
     */
    void assertLedgerConsistent() {
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
