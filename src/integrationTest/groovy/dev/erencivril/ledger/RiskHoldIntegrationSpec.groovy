package dev.erencivril.ledger

import dev.erencivril.ledger.application.ApplicationException
import dev.erencivril.ledger.domain.DomainException
import dev.erencivril.ledger.domain.TransferStatus
import dev.erencivril.ledger.domain.risk.RiskAction
import dev.erencivril.ledger.risk.RuleSetLoader
import dev.erencivril.ledger.risk.RuleSetYaml
import dev.erencivril.ledger.support.TestLedger
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static dev.erencivril.ledger.domain.Currency.*

/**
 * The risk engine against real PostgreSQL: the hold pattern, the audit table and the rule set storage.
 * The rules here are test rules (versions 9001 and up) so the bundled ones can change without breaking this.
 */
class RiskHoldIntegrationSpec extends Specification {

    static final String RULES = '''
version: 9001
description: Test rules for the hold specs
stack: {warnings: 0, stepUps: 0}
rules:
  - id: NEW_PAYEE_ACCOUNT
    action: BLOCK
    params: {maxAgeHours: 1, minAmountUsd: 5000}
  - id: FIRST_PAYMENT_LARGE
    action: HOLD
    params: {minAmountUsd: 100}
  - id: AMOUNT_DEVIATION
    action: STEP_UP
    params: {factor: 5, minHistory: 2}
'''

    @Shared TestLedger ledger

    def setupSpec() {
        ledger = new TestLedger()
        ledger.enableRisk(RULES)
    }

    def cleanupSpec() {
        ledger.assertLedgerConsistent()
        ledger.close()
    }

    String key() { "risk-${UUID.randomUUID()}".toString() }

    def "a first payment above the threshold is held: the sender is debited, the payee is not paid, the hold account has the money"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def heldBefore = ledger.holdBalance(USD)

        when:
        def result = ledger.transfers.transfer(ledger.command(key(), a, b, '150.00'))

        then:
        result.transfer().status() == TransferStatus.PENDING
        result.risk().action() == RiskAction.HOLD
        result.risk().reasonCodes() == ['FIRST_PAYMENT_LARGE']
        ledger.balanceOf(a.id()) == 850.00G
        ledger.balanceOf(b.id()) == 0G
        ledger.holdBalance(USD) - heldBefore == 150.00G
        ledger.assertLedgerConsistent()

        and: 'the decision is in the audit table with the version and the rule that fired'
        def row = ledger.sql.firstRow('select * from risk_decisions where transfer_id = ?', [result.transfer().id()])
        row.action == 'HOLD'
        row.rule_set_version == 9001
        row.first_payment
        row.amount_usd == 150.00G
        ledger.sql.rows('select rule_id, action from risk_decision_hits where decision_id = ?', [row.id])*.rule_id == ['FIRST_PAYMENT_LARGE']
    }

    def "release pays the payee from the hold account and completes the transfer"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '3.00')
        def heldBefore = ledger.holdBalance(USD)
        def held = ledger.transfers.transfer(ledger.command(key(), a, b, '200.00')).transfer()

        when:
        def released = ledger.holds.release(held.id())

        then:
        released.status() == TransferStatus.COMPLETED
        ledger.balanceOf(a.id()) == 800.00G
        ledger.balanceOf(b.id()) == 203.00G
        ledger.holdBalance(USD) == heldBefore
        ledger.transfers.get(held.id()).status() == TransferStatus.COMPLETED
        ledger.sql.firstRow('select resolved_at from transfers where id = ?', [held.id()]).resolved_at != null
        ledger.sql.firstRow('select count(*) c from journal_entries where transfer_id = ?', [held.id()]).c == 4
        ledger.assertLedgerConsistent()
    }

    def "reject gives the money back to the sender and the payee never sees it"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def heldBefore = ledger.holdBalance(USD)
        def held = ledger.transfers.transfer(ledger.command(key(), a, b, '200.00')).transfer()

        when:
        def rejected = ledger.holds.reject(held.id())

        then:
        rejected.status() == TransferStatus.REJECTED
        ledger.balanceOf(a.id()) == 1000.00G
        ledger.balanceOf(b.id()) == 0G
        ledger.holdBalance(USD) == heldBefore
        ledger.assertLedgerConsistent()
    }

    def "a decided transfer cannot be decided again, in either direction"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def held = ledger.transfers.transfer(ledger.command(key(), a, b, '200.00')).transfer()
        ledger.holds.release(held.id())

        when:
        ledger.holds.reject(held.id())

        then:
        thrown(DomainException.TransferNotPending)

        when:
        ledger.holds.release(held.id())

        then:
        thrown(DomainException.TransferNotPending)
        ledger.balanceOf(b.id()) == 200.00G
    }

    def "an ordinary completed transfer cannot be released or rejected"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def done = ledger.transfers.transfer(ledger.command(key(), a, b, '50.00')).transfer()

        when:
        ledger.holds.release(done.id())

        then:
        thrown(DomainException.TransferNotPending)
    }

    def "an unknown transfer id is a not-found error"() {
        when:
        ledger.holds.release(UUID.randomUUID())

        then:
        thrown(ApplicationException.TransferNotFound)
    }

    def "two people deciding the same hold at the same moment: exactly one wins, the money moves once"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def held = ledger.transfers.transfer(ledger.command(key(), a, b, '300.00')).transfer()
        def start = new CountDownLatch(1)
        def decide = { String how ->
            start.await(10, TimeUnit.SECONDS)
            try { ledger.holds."$how"(held.id()); 'won' } catch (DomainException.TransferNotPending e) { 'lost' }
        }

        when:
        def futures = [CompletableFuture.supplyAsync { decide('release') }, CompletableFuture.supplyAsync { decide('reject') },
                       CompletableFuture.supplyAsync { decide('release') }]
        start.countDown()
        def outcomes = futures*.get(30, TimeUnit.SECONDS)

        then:
        outcomes.count { it == 'won' } == 1
        outcomes.count { it == 'lost' } == 2
        def status = ledger.transfers.get(held.id()).status()
        status in [TransferStatus.COMPLETED, TransferStatus.REJECTED]
        ledger.balanceOf(b.id()) == (status == TransferStatus.COMPLETED ? 300.00G : 0G)
        ledger.balanceOf(a.id()) == (status == TransferStatus.COMPLETED ? 700.00G : 1000.00G)
        ledger.assertLedgerConsistent()
    }

    def "a cross-currency hold freezes the amount and the rate, and the release pays exactly that"() {
        given:
        def usd = ledger.openAccount(USD, '1000.00')
        def jpy = ledger.openAccount(JPY, null)
        def heldBefore = ledger.holdBalance(USD)

        when:
        def held = ledger.transfers.transfer(ledger.command(key(), usd, jpy, '150.00')).transfer()

        then:
        held.status() == TransferStatus.PENDING
        held.destinationAmount().amount() == 22680G
        ledger.balanceOf(jpy.id()) == 0G
        ledger.holdBalance(USD) - heldBefore == 150.00G

        when:
        ledger.holds.release(held.id())

        then:
        ledger.balanceOf(jpy.id()) == 22680G
        ledger.holdBalance(USD) == heldBefore
        ledger.assertLedgerConsistent()
    }

    def "a held payment does not make the payee known; a completed one does"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')

        when: 'the first payment is held and the second, still before any release, is also a first payment'
        def first = ledger.transfers.transfer(ledger.command(key(), a, b, '150.00'))
        def second = ledger.transfers.transfer(ledger.command(key(), a, b, '160.00'))

        then:
        first.risk().action() == RiskAction.HOLD
        second.risk().action() == RiskAction.HOLD

        when: 'one is released: now the payee is known and a payment of the same size is allowed'
        ledger.holds.release(first.transfer().id())
        def third = ledger.transfers.transfer(ledger.command(key(), a, b, '170.00'))

        then:
        third.risk().action() == RiskAction.ALLOW
        third.transfer().status() == TransferStatus.COMPLETED
    }

    def "a rejected hold does not make the payee known either"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def held = ledger.transfers.transfer(ledger.command(key(), a, b, '150.00')).transfer()
        ledger.holds.reject(held.id())

        expect:
        ledger.transfers.transfer(ledger.command(key(), a, b, '170.00')).risk().action() == RiskAction.HOLD
    }

    def "BLOCK writes an audit row, moves nothing and leaves the idempotency key free"() {
        given:
        def a = ledger.openAccount(USD, '9000.00')
        def b = ledger.openAccount(USD, '0')
        def k = key()

        when:
        ledger.transfers.transfer(ledger.command(k, a, b, '6000.00'))

        then:
        def e = thrown(ApplicationException.TransferBlocked)
        e.reasonCodes() == ['NEW_PAYEE_ACCOUNT', 'FIRST_PAYMENT_LARGE']
        ledger.balanceOf(a.id()) == 9000.00G
        ledger.balanceOf(b.id()) == 0G
        ledger.sql.firstRow('select count(*) c from idempotency_keys where idempotency_key = ?', [k]).c == 0
        def row = ledger.sql.firstRow('select * from risk_decisions where source_account_id = ?', [a.id()])
        row.action == 'BLOCK'
        row.transfer_id == null
    }

    def "STEP_UP stops the payment until the customer confirms, and a confirmed one goes ahead as a warning"() {
        given:
        def a = ledger.openAccount(USD, '5000.00')
        def b = ledger.openAccount(USD, '0')
        (1..3).each { ledger.transfers.transfer(ledger.command(key(), a, b, '10.00')) }
        def k = key()

        when:
        ledger.transfers.transfer(ledger.command(k, a, b, '60.00'))

        then:
        def e = thrown(ApplicationException.StepUpRequired)
        e.reasonCodes() == ['AMOUNT_DEVIATION']
        ledger.balanceOf(a.id()) == 4970.00G

        when: 'the same key is used again with the confirmation'
        def confirmed = ledger.transfers.transfer(new dev.erencivril.ledger.application.TransferService.TransferCommand(
                k, a.id(), b.id(), new dev.erencivril.ledger.domain.Money(60.00G, USD), true))

        then:
        confirmed.transfer().status() == TransferStatus.COMPLETED
        confirmed.risk().action() == RiskAction.WARN
        ledger.balanceOf(a.id()) == 4910.00G
        ledger.sql.rows('select action, step_up_confirmed from risk_decisions where source_account_id = ? order by created_at', [a.id()])
                .findAll { it.action in ['STEP_UP', 'WARN'] }*.step_up_confirmed == [false, true]
    }

    def "replaying a held transfer returns the same decision and does not hold twice"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def k = key()
        def first = ledger.transfers.transfer(ledger.command(k, a, b, '150.00'))

        when:
        def replay = ledger.transfers.transfer(ledger.command(k, a, b, '150.00'))

        then:
        replay.replayed()
        replay.transfer().id() == first.transfer().id()
        replay.risk().action() == RiskAction.HOLD
        replay.risk().reasonCodes() == ['FIRST_PAYMENT_LARGE']
        ledger.balanceOf(a.id()) == 850.00G
    }

    def "a hold the sender cannot afford fails like a transfer and leaves no audit row behind"() {
        given:
        def a = ledger.openAccount(USD, '120.00')
        def b = ledger.openAccount(USD, '0')

        when:
        ledger.transfers.transfer(ledger.command(key(), a, b, '150.00'))

        then:
        thrown(DomainException.InsufficientFunds)
        ledger.sql.firstRow('select count(*) c from risk_decisions where source_account_id = ?', [a.id()]).c == 0
    }

    def "crash between the hold and the release: the money waits in the hold account and a restarted process can finish the job"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def held = ledger.transfers.transfer(ledger.command(key(), a, b, '400.00')).transfer()

        when: 'the first process is gone; a new one starts from the database alone'
        def restarted = new TestLedger()
        def stillPending = restarted.transfers.get(held.id())
        restarted.holds.release(held.id())

        then:
        stillPending.status() == TransferStatus.PENDING
        restarted.balanceOf(b.id()) == 400.00G
        restarted.balanceOf(a.id()) == 600.00G
        restarted.assertLedgerConsistent()

        cleanup:
        restarted?.close()
    }

    def "a release that fails halfway rolls back completely and can be tried again"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def held = ledger.transfers.transfer(ledger.command(key(), a, b, '400.00')).transfer()

        when: 'the process dies after the payee was credited but before commit'
        ledger.unitOfWork.execute { tx ->
            tx.transfers().findForUpdate(held.id())
            tx.accounts().updateBalance(b.id(), new dev.erencivril.ledger.domain.Money(400.00G, USD))
            throw new IllegalStateException('simulated crash')
        }

        then:
        thrown(IllegalStateException)
        ledger.balanceOf(b.id()) == 0G
        ledger.transfers.get(held.id()).status() == TransferStatus.PENDING

        when:
        ledger.holds.release(held.id())

        then:
        ledger.balanceOf(b.id()) == 400.00G
    }

    def "the audit tables refuse updates and deletes, even from the application's own connection"() {
        given:
        def a = ledger.openAccount(USD, '1000.00')
        def b = ledger.openAccount(USD, '0')
        def held = ledger.transfers.transfer(ledger.command(key(), a, b, '150.00')).transfer()

        when:
        ledger.sql.executeUpdate("update risk_decisions set action = 'ALLOW' where transfer_id = ?", [held.id()])

        then:
        def update = thrown(java.sql.SQLException)
        update.message.contains('append-only')

        when:
        ledger.sql.execute("delete from risk_decision_hits where decision_id in (select id from risk_decisions where transfer_id = ?)", [held.id()])

        then:
        thrown(java.sql.SQLException)

        when:
        ledger.sql.execute('delete from risk_rule_sets where version = 9001')

        then:
        thrown(java.sql.SQLException)
    }

    def "a published rule set version cannot be changed: a different file under the same version stops the start"() {
        given:
        def loader = new RuleSetLoader(ledger.unitOfWork)
        def edited = RuleSetYaml.parse(RULES.replace('minAmountUsd: 100', 'minAmountUsd: 50'))

        when:
        loader.publish(edited, 'edited.yaml')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('9001')
        e.message.contains('edited.yaml')

        and: 'publishing the identical file again is fine'
        loader.publish(RuleSetYaml.parse(RULES), 'same.yaml') == 9001
    }

    def "the rule set read back from the database equals the one in the file"() {
        given:
        def parsed = RuleSetYaml.parse(RULES)

        when:
        def stored = ledger.unitOfWork.execute { tx -> tx.ruleSets().find(9001).get() }

        then:
        stored.ruleSet().version() == 9001
        stored.ruleSet().description() == parsed.ruleSet().description()
        stored.ruleSet().rules()*.id() == parsed.ruleSet().rules()*.id()
        stored.ruleSet().rules()*.action() == parsed.ruleSet().rules()*.action()
        // numeric(20,6) pads the scale (5 comes back as 5.000000), so compare the values, not the scale
        stored.ruleSet().rules().collect { r -> r.params().collectEntries { k, v -> [(k): v.stripTrailingZeros()] } } ==
                parsed.ruleSet().rules().collect { r -> r.params().collectEntries { k, v -> [(k): v.stripTrailingZeros()] } }
        stored.contentSha256() == parsed.sha256()
    }

    def "a rule set that names an unknown rule never reaches the database"() {
        given:
        def bad = RuleSetYaml.parse('version: 9002\ndescription: bad\nrules:\n  - {id: NO_SUCH_RULE, action: WARN}\n')

        when:
        new RuleSetLoader(ledger.unitOfWork).publish(bad, 'bad.yaml')

        then:
        thrown(IllegalArgumentException)
        ledger.sql.firstRow('select count(*) c from risk_rule_sets where version = 9002').c == 0
    }

    def "the bundled rule sets load once and the engine can be built from the stored copy"() {
        given:
        def loader = new RuleSetLoader(ledger.unitOfWork)

        expect:
        loader.loadBundled() == [1]
        loader.loadBundled() == [1]
        loader.engineFor(1).ruleSetVersion() == 1

        when:
        loader.engineFor(777)

        then:
        thrown(IllegalArgumentException)
    }
}
