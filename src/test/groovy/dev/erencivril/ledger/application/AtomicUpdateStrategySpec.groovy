package dev.erencivril.ledger.application

import dev.erencivril.ledger.application.Ports.*
import dev.erencivril.ledger.domain.DomainException
import spock.lang.Specification

import static dev.erencivril.ledger.application.StrategySupport.*

/**
 * No explicit lock and no version. The debit is one UPDATE ... WHERE balance >= amount, so the database
 * decides whether there is enough money at the moment of the write.
 */
class AtomicUpdateStrategySpec extends Specification {

    def accounts = Mock(AccountRepository)
    def tx = Stub(LedgerTransaction) { accounts() >> accounts }
    def strategy = new AtomicUpdateStrategy(planner())

    def setup() {
        accounts.find(LOW) >> Optional.of(account(LOW, '1.00'))   // stale or not, the read is only used for the currency
        accounts.find(HIGH) >> Optional.of(account(HIGH, '1.00'))
    }

    def "the balances it returns are the ones the UPDATE statements returned, not the ones it read"() {
        when:
        def change = strategy.changeBalances(tx, LOW, HIGH, usd('30.00'))

        then:
        1 * accounts.debitIfFunded(LOW, usd('30.00')) >> Optional.of(usd('70.00'))
        1 * accounts.credit(HIGH, usd('30.00')) >> usd('130.00')
        change.sourceBalanceAfter() == usd('70.00')
        change.destinationBalanceAfter() == usd('130.00')
        change.plan().sourceAmount() == usd('30.00')
        0 * accounts.findForUpdate(_)
        0 * accounts.findVersioned(_)
        0 * accounts.updateBalance(*_)
    }

    def "it touches the rows in ascending id order: debit first when the source id is lower"() {
        given:
        def calls = []
        accounts.debitIfFunded(_, _) >> { UUID id, m -> calls << "debit $id".toString(); Optional.of(usd('1.00')) }
        accounts.credit(_, _) >> { UUID id, m -> calls << "credit $id".toString(); usd('1.00') }

        when:
        strategy.changeBalances(tx, LOW, HIGH, usd('1.00'))

        then:
        calls == ["debit $LOW".toString(), "credit $HIGH".toString()]
    }

    def "it touches the rows in ascending id order: credit first when the destination id is lower"() {
        given:
        def calls = []
        accounts.debitIfFunded(_, _) >> { UUID id, m -> calls << "debit $id".toString(); Optional.of(usd('1.00')) }
        accounts.credit(_, _) >> { UUID id, m -> calls << "credit $id".toString(); usd('1.00') }

        when:
        strategy.changeBalances(tx, HIGH, LOW, usd('1.00'))

        then:
        calls == ["credit $LOW".toString(), "debit $HIGH".toString()]
    }

    def "when the UPDATE refuses the debit the transfer is rejected as insufficient funds"() {
        when:
        strategy.changeBalances(tx, LOW, HIGH, usd('500.00'))

        then:
        1 * accounts.debitIfFunded(LOW, usd('500.00')) >> Optional.empty()
        0 * accounts.credit(*_)
        def e = thrown(DomainException.InsufficientFunds)
        e.message.contains(LOW.toString())
    }

    def "when the credit comes first and the debit is then refused, the exception rolls the credit back"() {
        when:
        strategy.changeBalances(tx, HIGH, LOW, usd('500.00'))

        then:
        1 * accounts.credit(LOW, usd('500.00')) >> usd('501.00')
        1 * accounts.debitIfFunded(HIGH, usd('500.00')) >> Optional.empty()
        thrown(DomainException.InsufficientFunds)
    }

    def "an unknown account is reported before any UPDATE, source first"() {
        given:
        def missingAccounts = Mock(AccountRepository)
        def missingTx = Stub(LedgerTransaction) { accounts() >> missingAccounts }
        missingAccounts.find(LOW) >> lowFound
        missingAccounts.find(HIGH) >> Optional.empty()

        when:
        strategy.changeBalances(missingTx, LOW, HIGH, usd('1.00'))

        then:
        def e = thrown(ApplicationException.AccountNotFound)
        e.message.contains(missing.toString())
        0 * missingAccounts.debitIfFunded(*_)
        0 * missingAccounts.credit(*_)

        where:
        lowFound                          | missing
        Optional.empty()                  | LOW
        Optional.of(account(LOW, '9.00')) | HIGH
    }

    def "a transfer to the same account is rejected before any UPDATE"() {
        when:
        strategy.changeBalances(tx, LOW, LOW, usd('1.00'))

        then:
        thrown(DomainException.SameAccount)
        0 * accounts.debitIfFunded(*_)
        0 * accounts.credit(*_)
    }
}
