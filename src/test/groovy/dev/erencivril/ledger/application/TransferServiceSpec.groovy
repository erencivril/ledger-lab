package dev.erencivril.ledger.application

import dev.erencivril.ledger.application.Ports.*
import dev.erencivril.ledger.domain.*
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Function

import static dev.erencivril.ledger.domain.Currency.USD

/** Orchestration only, with every port mocked: what is asked of Redis and Postgres, and in what order. */
class TransferServiceSpec extends Specification {

    def clock = Clock.fixed(Instant.parse('2026-03-01T10:00:00.123456789Z'), ZoneOffset.UTC)
    def transfers = Mock(TransferRepository)
    def idempotency = Mock(IdempotencyRepository)
    def accounts = Mock(AccountRepository)
    def journal = Mock(JournalRepository)
    def tx = Stub(LedgerTransaction) {
        transfers() >> transfers
        idempotency() >> idempotency
        accounts() >> accounts
        journal() >> journal
    }
    def unitOfWork = Mock(UnitOfWork)
    def limiter = Mock(VelocityLimiter)
    def cache = Mock(IdempotencyCache)
    def planner = new TransferPlanner(new FxRateTable([]), new TreasuryAccounts([(USD): UUID.randomUUID()]))
    def pause = Mock(Pause)
    def service = new TransferService(
            unitOfWork, new PessimisticLockStrategy(planner), limiter, cache, clock, new RetryPolicy(3, 5), pause)

    def from = account('100.00')
    def to = account('0.00')
    def command = new TransferService.TransferCommand('key-1', from.id(), to.id(), usd('25.00'))
    def fingerprint = command.fingerprint()
    def stored = new Transfer(UUID.randomUUID(), TransferKind.TRANSFER, from.id(), to.id(), usd('25.00'), usd('25.00'), null, clock.instant())

    def setup() {
        unitOfWork.execute(_) >> { Function f -> f.apply(tx) }
        accounts.find(from.id()) >> Optional.of(from)
        accounts.find(to.id()) >> Optional.of(to)
    }

    static Money usd(String amount) { new Money(new BigDecimal(amount), USD) }

    static Account account(String balance) {
        new Account(UUID.randomUUID(), 'o', USD, AccountKind.CUSTOMER, usd(balance), Instant.EPOCH)
    }

    def "a cache hit for the same request returns the stored transfer and skips the velocity check and any write"() {
        when:
        def result = service.transfer(command)

        then:
        1 * cache.get('key-1') >> Optional.of(new CachedKey(fingerprint, stored.id()))
        1 * transfers.find(stored.id()) >> Optional.of(stored)
        0 * limiter.acquire(_)
        0 * idempotency.tryClaim(_, _)
        0 * cache.put(*_)
        result.replayed()
        result.transfer() == stored
    }

    def "a cache hit for a different request is a conflict without touching the database"() {
        given:
        def other = new TransferService.TransferCommand('key-1', from.id(), to.id(), usd('26.00'))

        when:
        service.transfer(other)

        then:
        1 * cache.get('key-1') >> Optional.of(new CachedKey(fingerprint, stored.id()))
        thrown(ApplicationException.IdempotencyKeyReused)
        0 * limiter.acquire(_)
        0 * transfers.find(_)
        0 * idempotency.tryClaim(_, _)
    }

    def "a stale cache entry that points at a missing transfer falls back to Postgres"() {
        when:
        def result = service.transfer(command)

        then:
        1 * cache.get('key-1') >> Optional.of(new CachedKey(fingerprint, stored.id()))
        1 * transfers.find(stored.id()) >> Optional.empty()
        1 * idempotency.find('key-1') >> Optional.of(new IdempotencyRecord('key-1', fingerprint, stored.id()))
        1 * transfers.find(stored.id()) >> Optional.of(stored)
        result.replayed()
    }

    def "an unseen key goes through the velocity check, then one transaction, then the cache is filled"() {
        when:
        def result = service.transfer(command)

        then: 'lookups first'
        1 * cache.get('key-1') >> Optional.empty()
        1 * idempotency.find('key-1') >> Optional.empty()

        then: 'risk check before the money transaction'
        1 * limiter.acquire(from.id())

        then: 'claim, lock in id order, write'
        1 * idempotency.tryClaim('key-1', fingerprint) >> true
        1 * accounts.findForUpdate(_) >> { UUID id -> Optional.of(id == from.id() ? from : to) }
        1 * accounts.findForUpdate(_) >> { UUID id -> Optional.of(id == from.id() ? from : to) }
        1 * transfers.insert(_)
        1 * accounts.updateBalance(from.id(), usd('75.00'))
        1 * accounts.updateBalance(to.id(), usd('25.00'))
        1 * journal.append(_, { it.size() == 2 }, Instant.parse('2026-03-01T10:00:00.123456Z'))
        1 * idempotency.attach('key-1', _)

        then:
        1 * cache.put('key-1', fingerprint, _)
        !result.replayed()
        result.transfer().createdAt() == Instant.parse('2026-03-01T10:00:00.123456Z')
    }

    def "accounts are locked in ascending id order whichever way the money flows"() {
        given:
        def low = new Account(UUID.fromString('00000000-0000-0000-0000-000000000001'), 'o', USD, AccountKind.CUSTOMER, usd('50.00'), Instant.EPOCH)
        def high = new Account(UUID.fromString('ffffffff-0000-0000-0000-000000000001'), 'o', USD, AccountKind.CUSTOMER, usd('50.00'), Instant.EPOCH)
        4 * accounts.find(_) >> { UUID id -> Optional.of(id == low.id() ? low : high) }
        def lockOrder = []
        accounts.findForUpdate(_) >> { UUID id -> lockOrder << id; Optional.of(id == low.id() ? low : high) }
        cache.get(_) >> Optional.empty()
        idempotency.find(_) >> Optional.empty()
        idempotency.tryClaim(_, _) >> true

        when:
        service.transfer(new TransferService.TransferCommand('a', low.id(), high.id(), usd('1.00')))
        service.transfer(new TransferService.TransferCommand('b', high.id(), low.id(), usd('1.00')))

        then:
        def ascending = [low.id(), high.id()].sort() // UUID.compareTo: any fixed total order works, as long as everyone uses it
        lockOrder == ascending + ascending
    }

    def "when the limiter rejects, no transaction claims the key and nothing is cached"() {
        when:
        service.transfer(command)

        then:
        1 * cache.get(_) >> Optional.empty()
        1 * idempotency.find(_) >> Optional.empty()
        1 * limiter.acquire(from.id()) >> { throw new ApplicationException.VelocityLimitExceeded(from.id(), 10, 60, 7) }
        thrown(ApplicationException.VelocityLimitExceeded)
        0 * idempotency.tryClaim(_, _)
        0 * cache.put(*_)
    }

    def "losing the claim race returns the winner's transfer as a replay"() {
        when:
        def result = service.transfer(command)

        then:
        1 * cache.get(_) >> Optional.empty()
        2 * idempotency.find('key-1') >>> [Optional.empty(), Optional.of(new IdempotencyRecord('key-1', fingerprint, stored.id()))]
        1 * limiter.acquire(_)
        1 * idempotency.tryClaim('key-1', fingerprint) >> false
        1 * transfers.find(stored.id()) >> Optional.of(stored)
        0 * accounts.findForUpdate(_)
        result.replayed()
        result.transfer() == stored
    }

    def "losing the claim race with a different body is a conflict"() {
        when:
        service.transfer(command)

        then:
        1 * cache.get(_) >> Optional.empty()
        2 * idempotency.find('key-1') >>> [Optional.empty(), Optional.of(new IdempotencyRecord('key-1', 'someone-elses-hash', stored.id()))]
        1 * limiter.acquire(_)
        1 * idempotency.tryClaim('key-1', fingerprint) >> false
        thrown(ApplicationException.IdempotencyKeyReused)
    }

    def "a missing account is reported before planning, source first"() {
        when:
        service.transfer(command)

        then:
        1 * cache.get(_) >> Optional.empty()
        1 * idempotency.find(_) >> Optional.empty()
        0 * limiter.acquire(_)
        0 * idempotency.tryClaim(_, _)
        1 * accounts.find(from.id()) >> Optional.empty()
        def e = thrown(ApplicationException.AccountNotFound)
        e.message.contains(from.id().toString())
        0 * transfers.insert(_)
    }

    def "the fingerprint ignores how the amount is written but not what it says"() {
        expect:
        fp(usd('25')) == fp(usd('25.00'))
        fp(usd('25.00')) != fp(usd('25.01'))
        new TransferService.TransferCommand('k', from.id(), to.id(), usd('1')).fingerprint() !=
                new TransferService.TransferCommand('k', to.id(), from.id(), usd('1')).fingerprint()
        fp(usd('1')) != new TransferService.TransferCommand('k', from.id(), to.id(), new Money(BigDecimal.ONE, Currency.EUR)).fingerprint()
        fp(usd('1')).length() == 64
    }

    private String fp(Money amount) {
        new TransferService.TransferCommand('k', from.id(), to.id(), amount).fingerprint()
    }

    def "get returns the stored transfer or a not-found error"() {
        when:
        def found = service.get(stored.id())

        then:
        1 * transfers.find(stored.id()) >> Optional.of(stored)
        found == stored

        when:
        service.get(stored.id())

        then:
        1 * transfers.find(stored.id()) >> Optional.empty()
        thrown(ApplicationException.TransferNotFound)
    }

    // ---- retries -------------------------------------------------------------------------------------------

    def "a ConcurrentUpdate rolls back the attempt and the whole transaction is run again, claim included"() {
        given:
        def strategy = Mock(TransferStrategy)
        def retrying = new TransferService(unitOfWork, strategy, limiter, cache, clock, new RetryPolicy(3, 5), pause)
        def change = new BalanceChange(
                planner.plan(from, to, usd('25.00')), usd('75.00'), usd('25.00'))
        def attempts = 0

        when:
        def result = retrying.transfer(command)

        then:
        1 * cache.get(_) >> Optional.empty()
        1 * idempotency.find(_) >> Optional.empty()
        1 * limiter.acquire(from.id())
        3 * idempotency.tryClaim('key-1', fingerprint) >> true
        3 * strategy.changeBalances(tx, from.id(), to.id(), usd('25.00')) >> {
            if (++attempts < 3) {
                throw new ApplicationException.ConcurrentUpdate('lost the race')
            }
            change
        }
        2 * pause.pause({ it >= 0 && it <= 10 })
        1 * transfers.insert(_)
        1 * journal.append(_, _, _)
        !result.replayed()
        retrying.retries() == 2
    }

    def "after the last allowed attempt the ConcurrentUpdate reaches the caller and nothing is cached"() {
        given:
        def strategy = Mock(TransferStrategy)
        def retrying = new TransferService(unitOfWork, strategy, limiter, cache, clock, new RetryPolicy(3, 5), pause)

        when:
        retrying.transfer(command)

        then:
        1 * cache.get(_) >> Optional.empty()
        1 * idempotency.find(_) >> Optional.empty()
        3 * idempotency.tryClaim(*_) >> true
        3 * strategy.changeBalances(*_) >> { throw new ApplicationException.ConcurrentUpdate('lost the race') }
        2 * pause.pause(_)
        0 * cache.put(*_)
        thrown(ApplicationException.ConcurrentUpdate)
        retrying.retries() == 2
    }

    def "a business rejection is never retried"() {
        given:
        def strategy = Mock(TransferStrategy)
        def retrying = new TransferService(unitOfWork, strategy, limiter, cache, clock, new RetryPolicy(3, 5), pause)

        when:
        retrying.transfer(command)

        then:
        1 * cache.get(_) >> Optional.empty()
        1 * idempotency.find(_) >> Optional.empty()
        1 * idempotency.tryClaim(*_) >> true
        1 * strategy.changeBalances(*_) >> { throw new DomainException.InsufficientFunds(from.id(), usd('1.00'), usd('25.00')) }
        0 * pause.pause(_)
        thrown(DomainException.InsufficientFunds)
        retrying.retries() == 0
    }

    def "a service with one attempt never retries"() {
        given:
        def strategy = Mock(TransferStrategy)
        def single = new TransferService(unitOfWork, strategy, limiter, cache, clock, new RetryPolicy(1, 5), pause)

        when:
        single.transfer(command)

        then:
        1 * cache.get(_) >> Optional.empty()
        1 * idempotency.find(_) >> Optional.empty()
        1 * idempotency.tryClaim(*_) >> true
        1 * strategy.changeBalances(*_) >> { throw new ApplicationException.ConcurrentUpdate('lost the race') }
        0 * pause.pause(_)
        thrown(ApplicationException.ConcurrentUpdate)
    }
}
