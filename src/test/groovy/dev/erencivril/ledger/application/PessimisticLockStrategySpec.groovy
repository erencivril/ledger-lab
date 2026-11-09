package dev.erencivril.ledger.application

import dev.erencivril.ledger.application.Ports.*
import dev.erencivril.ledger.domain.DomainException
import spock.lang.Specification

import static dev.erencivril.ledger.application.StrategySupport.*

/** SELECT ... FOR UPDATE on both rows in ascending id order, then plain updates. */
class PessimisticLockStrategySpec extends Specification {

    def accounts = Mock(AccountRepository)
    def tx = Stub(LedgerTransaction) { accounts() >> accounts }
    def strategy = new PessimisticLockStrategy(planner())

    def "it locks both accounts in ascending id order whichever way the money flows"() {
        given:
        def locked = []
        accounts.findForUpdate(_) >> { UUID id -> locked << id; Optional.of(account(id, '50.00')) }

        when:
        strategy.changeBalances(tx, from, to, usd('1.00'))

        then:
        locked == [LOW, HIGH]

        where:
        from | to
        LOW  | HIGH
        HIGH | LOW
    }

    def "it writes both new balances and returns them with the plan"() {
        given:
        accounts.findForUpdate(LOW) >> Optional.of(account(LOW, '100.00'))
        accounts.findForUpdate(HIGH) >> Optional.of(account(HIGH, '5.00'))

        when:
        def change = strategy.changeBalances(tx, LOW, HIGH, usd('30.25'))

        then:
        1 * accounts.updateBalance(LOW, usd('69.75'))
        1 * accounts.updateBalance(HIGH, usd('35.25'))
        change.sourceBalanceAfter() == usd('69.75')
        change.destinationBalanceAfter() == usd('35.25')
        change.plan().sourceAmount() == usd('30.25')
        0 * accounts.findVersioned(_)
        0 * accounts.updateBalanceIfVersion(*_)
        0 * accounts.debitIfFunded(*_)
    }

    def "a missing source is reported first, then a missing destination"() {
        given:
        accounts.findForUpdate(LOW) >> lowFound
        accounts.findForUpdate(HIGH) >> highFound

        when:
        strategy.changeBalances(tx, LOW, HIGH, usd('1.00'))

        then:
        def e = thrown(ApplicationException.AccountNotFound)
        e.message.contains(missing.toString())
        0 * accounts.updateBalance(*_)

        where:
        lowFound                             | highFound                            | missing
        Optional.empty()                     | Optional.empty()                     | LOW
        Optional.of(account(LOW, '9.00'))    | Optional.empty()                     | HIGH
    }

    def "it refuses to overdraw and writes nothing"() {
        given:
        accounts.findForUpdate(LOW) >> Optional.of(account(LOW, '1.00'))
        accounts.findForUpdate(HIGH) >> Optional.of(account(HIGH, '0.00'))

        when:
        strategy.changeBalances(tx, LOW, HIGH, usd('1.01'))

        then:
        thrown(DomainException.InsufficientFunds)
        0 * accounts.updateBalance(*_)
    }

    def "a transfer to the same account takes one lock and is rejected"() {
        when:
        strategy.changeBalances(tx, LOW, LOW, usd('1.00'))

        then:
        1 * accounts.findForUpdate(LOW) >> Optional.of(account(LOW, '9.00'))
        thrown(DomainException.SameAccount)
        0 * accounts.updateBalance(*_)
    }
}
