package dev.erencivril.ledger.application

import dev.erencivril.ledger.application.Ports.*
import dev.erencivril.ledger.domain.*
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Function

import static dev.erencivril.ledger.domain.Currency.USD

class HoldServiceSpec extends Specification {

    def clock = Clock.fixed(Instant.parse('2026-03-01T10:00:00.123456789Z'), ZoneOffset.UTC)
    def transfers = Mock(TransferRepository)
    def accounts = Mock(AccountRepository)
    def journal = Mock(JournalRepository)
    def tx = Stub(LedgerTransaction) {
        transfers() >> transfers
        accounts() >> accounts
        journal() >> journal
    }
    def unitOfWork = Mock(UnitOfWork)
    def holdAccountId = UUID.randomUUID()
    def planner = new TransferPlanner(new FxRateTable([]), new TreasuryAccounts([(USD): UUID.randomUUID()]),
            new HoldAccounts([(USD): holdAccountId]))
    def service = new HoldService(unitOfWork, planner, clock)

    def from = account('70.00')
    def to = account('5.00')
    def pending = new Transfer(UUID.randomUUID(), TransferKind.TRANSFER, from.id(), to.id(), usd('30.00'), usd('30.00'), null,
            Instant.parse('2026-03-01T09:00:00Z'), TransferStatus.PENDING)
    def now = Instant.parse('2026-03-01T10:00:00.123456Z')

    def setup() {
        unitOfWork.execute(_) >> { Function f -> f.apply(tx) }
    }

    static Money usd(String amount) { new Money(new BigDecimal(amount), USD) }

    static Account account(String balance) {
        new Account(UUID.randomUUID(), 'o', USD, AccountKind.CUSTOMER, usd(balance), Instant.EPOCH)
    }

    def "release locks the transfer first, pays the payee from the hold account and marks it COMPLETED"() {
        when:
        def result = service.release(pending.id())

        then:
        1 * transfers.findForUpdate(pending.id()) >> Optional.of(pending)

        then:
        1 * accounts.findForUpdate(to.id()) >> Optional.of(to)
        1 * accounts.updateBalance(to.id(), usd('35.00'))
        1 * journal.append(pending.id(), { entries ->
            entries.size() == 2 &&
                    entries[0].accountId() == holdAccountId && entries[0].amount() == usd('-30.00') && entries[0].balanceAfter() == null &&
                    entries[1].accountId() == to.id() && entries[1].amount() == usd('30.00') && entries[1].balanceAfter() == usd('35.00')
        }, now)
        1 * transfers.resolve(pending.id(), TransferStatus.COMPLETED, now)
        result.status() == TransferStatus.COMPLETED
    }

    def "reject pays the sender back from the hold account and marks it REJECTED"() {
        when:
        def result = service.reject(pending.id())

        then:
        1 * transfers.findForUpdate(pending.id()) >> Optional.of(pending)

        then:
        1 * accounts.findForUpdate(from.id()) >> Optional.of(from)
        1 * accounts.updateBalance(from.id(), usd('100.00'))
        1 * journal.append(pending.id(), { entries ->
            entries[0].accountId() == holdAccountId && entries[0].amount() == usd('-30.00') &&
                    entries[1].accountId() == from.id() && entries[1].amount() == usd('30.00') && entries[1].balanceAfter() == usd('100.00')
        }, now)
        1 * transfers.resolve(pending.id(), TransferStatus.REJECTED, now)
        result.status() == TransferStatus.REJECTED
    }

    def "an unknown transfer is a not-found error"() {
        when:
        service.release(pending.id())

        then:
        1 * transfers.findForUpdate(pending.id()) >> Optional.empty()
        thrown(ApplicationException.TransferNotFound)
    }

    def "a transfer that is already decided cannot be decided again and nothing is written: #status"() {
        given:
        def done = new Transfer(pending.id(), pending.kind(), from.id(), to.id(), usd('30.00'), usd('30.00'), null, pending.createdAt(), status)

        when:
        service."$method"(pending.id())

        then:
        1 * transfers.findForUpdate(pending.id()) >> Optional.of(done)
        0 * journal.append(*_)
        0 * transfers.resolve(*_)
        0 * accounts.updateBalance(*_)
        thrown(DomainException.TransferNotPending)

        where:
        status                    | method
        TransferStatus.COMPLETED  | 'release'
        TransferStatus.COMPLETED  | 'reject'
        TransferStatus.REJECTED   | 'release'
        TransferStatus.REJECTED   | 'reject'
    }

    def "a missing payee account is reported, not hidden"() {
        when:
        service.release(pending.id())

        then:
        1 * transfers.findForUpdate(pending.id()) >> Optional.of(pending)
        1 * accounts.findForUpdate(to.id()) >> Optional.empty()
        thrown(ApplicationException.AccountNotFound)
    }
}
