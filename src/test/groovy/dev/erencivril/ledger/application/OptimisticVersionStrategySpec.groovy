package dev.erencivril.ledger.application

import dev.erencivril.ledger.application.Ports.*
import dev.erencivril.ledger.domain.DomainException
import spock.lang.Specification

import static dev.erencivril.ledger.application.StrategySupport.*

/** Plain reads, then UPDATE ... WHERE version = ? in ascending id order. A lost race is a ConcurrentUpdate. */
class OptimisticVersionStrategySpec extends Specification {

    def accounts = Mock(AccountRepository)
    def tx = Stub(LedgerTransaction) { accounts() >> accounts }
    def strategy = new OptimisticVersionStrategy(planner())

    def lowAt(String balance, long version) { Optional.of(new VersionedAccount(account(LOW, balance), version)) }

    def highAt(String balance, long version) { Optional.of(new VersionedAccount(account(HIGH, balance), version)) }

    def "it reads without locking and writes each balance with the version it read"() {
        given:
        accounts.findVersioned(LOW) >> lowAt('100.00', 7)
        accounts.findVersioned(HIGH) >> highAt('5.00', 3)

        when:
        def change = strategy.changeBalances(tx, LOW, HIGH, usd('30.25'))

        then:
        1 * accounts.updateBalanceIfVersion(LOW, usd('69.75'), 7) >> true
        1 * accounts.updateBalanceIfVersion(HIGH, usd('35.25'), 3) >> true
        change.sourceBalanceAfter() == usd('69.75')
        change.destinationBalanceAfter() == usd('35.25')
        0 * accounts.findForUpdate(_)
        0 * accounts.updateBalance(*_)
    }

    def "it writes in ascending id order whichever way the money flows, so two writers cannot deadlock"() {
        given:
        accounts.findVersioned(LOW) >> lowAt('50.00', 1)
        accounts.findVersioned(HIGH) >> highAt('50.00', 1)
        def written = []
        accounts.updateBalanceIfVersion(_, _, _) >> { UUID id, m, v -> written << id; true }

        when:
        strategy.changeBalances(tx, from, to, usd('1.00'))

        then:
        written == [LOW, HIGH]

        where:
        from | to
        LOW  | HIGH
        HIGH | LOW
    }

    def "if the first write finds a newer version it stops with a ConcurrentUpdate and does not write the second"() {
        given:
        accounts.findVersioned(LOW) >> lowAt('100.00', 7)
        accounts.findVersioned(HIGH) >> highAt('5.00', 3)

        when:
        strategy.changeBalances(tx, LOW, HIGH, usd('1.00'))

        then:
        1 * accounts.updateBalanceIfVersion(LOW, _, 7) >> false
        0 * accounts.updateBalanceIfVersion(HIGH, *_)
        def e = thrown(ApplicationException.ConcurrentUpdate)
        e.code() == 'concurrent_update'
    }

    def "if the second write finds a newer version it is also a ConcurrentUpdate"() {
        given:
        accounts.findVersioned(LOW) >> lowAt('100.00', 7)
        accounts.findVersioned(HIGH) >> highAt('5.00', 3)

        when:
        strategy.changeBalances(tx, LOW, HIGH, usd('1.00'))

        then:
        1 * accounts.updateBalanceIfVersion(LOW, _, 7) >> true
        1 * accounts.updateBalanceIfVersion(HIGH, _, 3) >> false
        thrown(ApplicationException.ConcurrentUpdate)
    }

    def "a missing source is reported first, then a missing destination"() {
        given:
        accounts.findVersioned(LOW) >> lowFound
        accounts.findVersioned(HIGH) >> Optional.empty()

        when:
        strategy.changeBalances(tx, LOW, HIGH, usd('1.00'))

        then:
        def e = thrown(ApplicationException.AccountNotFound)
        e.message.contains(missing.toString())
        0 * accounts.updateBalanceIfVersion(*_)

        where:
        lowFound         | missing
        Optional.empty() | LOW
        lowAt('9.00', 1) | HIGH
    }

    def "an overdraft seen in the read is refused before any write"() {
        given:
        accounts.findVersioned(LOW) >> lowAt('1.00', 7)
        accounts.findVersioned(HIGH) >> highAt('0.00', 3)

        when:
        strategy.changeBalances(tx, LOW, HIGH, usd('1.01'))

        then:
        thrown(DomainException.InsufficientFunds)
        0 * accounts.updateBalanceIfVersion(*_)
    }

    def "a transfer to the same account is rejected before any write"() {
        given:
        accounts.findVersioned(LOW) >> lowAt('9.00', 1)

        when:
        strategy.changeBalances(tx, LOW, LOW, usd('1.00'))

        then:
        thrown(DomainException.SameAccount)
        0 * accounts.updateBalanceIfVersion(*_)
    }
}
