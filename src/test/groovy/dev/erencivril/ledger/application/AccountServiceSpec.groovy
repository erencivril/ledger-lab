package dev.erencivril.ledger.application

import dev.erencivril.ledger.application.Ports.*
import dev.erencivril.ledger.domain.*
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Function

import static dev.erencivril.ledger.domain.Currency.*

class AccountServiceSpec extends Specification {

    def treasuryId = UUID.randomUUID()
    def accounts = Mock(AccountRepository)
    def transfers = Mock(TransferRepository)
    def journal = Mock(JournalRepository)
    def tx = Stub(LedgerTransaction) {
        accounts() >> accounts
        transfers() >> transfers
        journal() >> journal
    }
    def unitOfWork = Stub(UnitOfWork) {
        execute(_) >> { Function f -> f.apply(tx) }
    }
    def planner = new TransferPlanner(new FxRateTable([]), new TreasuryAccounts([(USD): treasuryId, (JPY): UUID.randomUUID()]))
    def service = new AccountService(unitOfWork, planner, Clock.fixed(Instant.parse('2026-03-01T10:00:00Z'), ZoneOffset.UTC))

    static Money usd(String amount) { new Money(new BigDecimal(amount), USD) }

    def "an account without an opening balance is just inserted"() {
        when:
        def account = service.open('Eren', USD, null)

        then:
        1 * accounts.insert({ it.owner() == 'Eren' && it.balance() == usd('0') })
        0 * transfers.insert(_)
        0 * journal.append(*_)
        account.balance() == usd('0')
    }

    def "a zero opening balance is treated like none"() {
        when:
        service.open('Eren', USD, usd('0.00'))

        then:
        1 * accounts.insert(_)
        0 * transfers.insert(_)
    }

    def "an opening balance is funded by the treasury with a two-entry journal"() {
        when:
        def account = service.open('Eren', USD, usd('50.00'))

        then:
        1 * accounts.insert(_)
        1 * transfers.insert({ it.kind() == TransferKind.OPENING_DEPOSIT && it.sourceAccountId() == treasuryId })
        1 * accounts.updateBalance(_, usd('50.00'))
        1 * journal.append(_, { List entries ->
            entries.size() == 2 &&
                    entries[0] == new NewJournalEntry(treasuryId, usd('-50.00'), null) &&
                    entries[1].amount() == usd('50.00') && entries[1].balanceAfter() == usd('50.00')
        }, _)
        account.balance() == usd('50.00')
    }

    def "an opening balance in another currency, a negative one and a bad owner are rejected before any write"() {
        when:
        service.open(owner, USD, balance)

        then:
        thrown(expected)
        0 * accounts.insert(_)

        where:
        owner | balance                                   | expected
        'a'   | new Money(BigDecimal.TEN, JPY)            | DomainException.CurrencyMismatch
        'a'   | usd('-5.00')                              | DomainException.InvalidAmount
        ' '   | usd('5.00')                               | DomainException.InvalidOwner
    }

    def "get and statement report a missing account"() {
        when:
        service.get(UUID.randomUUID())

        then:
        1 * accounts.find(_) >> Optional.empty()
        thrown(ApplicationException.AccountNotFound)

        when:
        service.statement(UUID.randomUUID(), null, null)

        then:
        1 * accounts.find(_) >> Optional.empty()
        thrown(ApplicationException.AccountNotFound)
    }

    def "statement uses the default page size unless a limit is given"() {
        given:
        def id = UUID.randomUUID()
        accounts.find(id) >> Optional.of(new Account(id, 'o', USD, AccountKind.CUSTOMER, usd('0'), Instant.EPOCH))

        when:
        service.statement(id, limit, 7L)

        then:
        1 * journal.statement(id, size, 7L) >> new StatementPage([], null)

        where:
        limit | size
        null  | 20
        5     | 5
    }
}
