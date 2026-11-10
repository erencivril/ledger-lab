package dev.erencivril.ledger.application

import dev.erencivril.ledger.application.Ports.*
import dev.erencivril.ledger.domain.*
import dev.erencivril.ledger.domain.risk.*
import spock.lang.Specification

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Function

import static dev.erencivril.ledger.domain.Currency.USD

/** What TransferService does with each risk action. Every port is mocked; the engine decision is canned. */
class TransferServiceRiskSpec extends Specification {

    def clock = Clock.fixed(Instant.parse('2026-03-01T10:00:00Z'), ZoneOffset.UTC)
    def transfers = Mock(TransferRepository)
    def idempotency = Mock(IdempotencyRepository)
    def accounts = Mock(AccountRepository)
    def journal = Mock(JournalRepository)
    def riskRepo = Mock(RiskRepository)
    def tx = Stub(LedgerTransaction) {
        transfers() >> transfers
        idempotency() >> idempotency
        accounts() >> accounts
        journal() >> journal
        risk() >> riskRepo
    }
    def unitOfWork = Mock(UnitOfWork)
    def limiter = Mock(VelocityLimiter)
    def cache = Mock(IdempotencyCache)
    def holdAccountId = UUID.randomUUID()
    def planner = new TransferPlanner(new FxRateTable([]), new TreasuryAccounts([(USD): UUID.randomUUID()]),
            new HoldAccounts([(USD): holdAccountId]))
    def risk = Mock(RiskAssessor)
    def holds = new HoldService(unitOfWork, planner, clock)
    def service = new TransferService(unitOfWork, new PessimisticLockStrategy(planner), limiter, cache, clock,
            new RetryPolicy(3, 5), Mock(Pause), risk, holds)

    def from = account('100.00')
    def to = account('0.00')
    def command = new TransferService.TransferCommand('key-1', from.id(), to.id(), usd('25.00'))

    def setup() {
        unitOfWork.execute(_) >> { Function f -> f.apply(tx) }
        cache.get(_) >> Optional.empty()
        idempotency.find(_) >> Optional.empty()
        idempotency.tryClaim(_, _) >> true
        accounts.findForUpdate(_) >> { UUID id -> Optional.of(id == from.id() ? from : to) }
        accounts.find(_) >> { UUID id -> Optional.of(id == from.id() ? from : to) }
    }

    static Money usd(String amount) { new Money(new BigDecimal(amount), USD) }

    static Account account(String balance) {
        new Account(UUID.randomUUID(), 'o', USD, AccountKind.CUSTOMER, usd(balance), Instant.EPOCH)
    }

    RiskAssessment assessmentOf(RiskAction action, List<String> codes = ['R1']) {
        def context = new RiskContext(usd('25.00'), new BigDecimal('25.00'), true, Duration.ofHours(1), 0, null, false)
        new RiskAssessment(context, new RiskDecision(action, codes.collect { new RuleHit(it, action == RiskAction.ALLOW ? RiskAction.WARN : action, 'x') }, 5))
    }

    def "BLOCK writes the audit row, moves no money and throws with the reason codes"() {
        when:
        service.transfer(command)

        then:
        1 * risk.assess(command) >> Optional.of(assessmentOf(RiskAction.BLOCK, ['A', 'B']))
        1 * riskRepo.insertDecision({ it.transferId() == null && it.decision().action() == RiskAction.BLOCK })
        0 * idempotency.tryClaim(*_)
        0 * accounts.updateBalance(*_)
        0 * transfers.insert(_)
        0 * journal.append(*_)
        def e = thrown(ApplicationException.TransferBlocked)
        e.reasonCodes() == ['A', 'B']
        e.code() == 'blocked_by_risk'
    }

    def "an unconfirmed STEP_UP is refused the same way, with its own code"() {
        when:
        service.transfer(command)

        then:
        1 * risk.assess(command) >> Optional.of(assessmentOf(RiskAction.STEP_UP))
        1 * riskRepo.insertDecision({ it.transferId() == null && it.decision().action() == RiskAction.STEP_UP })
        0 * transfers.insert(_)
        def e = thrown(ApplicationException.StepUpRequired)
        e.reasonCodes() == ['R1']
        e.code() == 'step_up_required'
    }

    def "ALLOW and WARN move the money and store the decision next to the transfer: #action"() {
        when:
        def result = service.transfer(command)

        then:
        1 * risk.assess(command) >> Optional.of(assessmentOf(action))
        1 * accounts.updateBalance(from.id(), usd('75.00'))
        1 * accounts.updateBalance(to.id(), usd('25.00'))
        1 * transfers.insert({ it.status() == TransferStatus.COMPLETED })
        1 * riskRepo.insertDecision({ it.transferId() != null && it.decision().action() == action })
        result.transfer().status() == TransferStatus.COMPLETED
        result.risk().action() == action

        where:
        action << [RiskAction.ALLOW, RiskAction.WARN]
    }

    def "HOLD takes the money from the sender, parks it in the hold account and leaves the payee unpaid"() {
        when:
        def result = service.transfer(command)

        then:
        1 * risk.assess(command) >> Optional.of(assessmentOf(RiskAction.HOLD))
        1 * accounts.updateBalance(from.id(), usd('75.00'))
        0 * accounts.updateBalance(to.id(), _)
        1 * transfers.insert({ it.status() == TransferStatus.PENDING && it.sourceAmount() == usd('25.00') })
        1 * journal.append(_, { entries ->
            entries.size() == 2 &&
                    entries[0].accountId() == from.id() && entries[0].amount() == usd('-25.00') && entries[0].balanceAfter() == usd('75.00') &&
                    entries[1].accountId() == holdAccountId && entries[1].amount() == usd('25.00') && entries[1].balanceAfter() == null
        }, _)
        1 * riskRepo.insertDecision({ it.transferId() != null && it.decision().action() == RiskAction.HOLD })
        result.transfer().status() == TransferStatus.PENDING
        result.risk().action() == RiskAction.HOLD
    }

    def "a HOLD the sender cannot afford fails the same way a transfer would"() {
        given:
        def tooMuch = new TransferService.TransferCommand('key-2', from.id(), to.id(), usd('150.00'))

        when:
        service.transfer(tooMuch)

        then:
        1 * risk.assess(tooMuch) >> Optional.of(assessmentOf(RiskAction.HOLD))
        thrown(DomainException.InsufficientFunds)
        0 * transfers.insert(_)
        0 * riskRepo.insertDecision(_)
    }

    def "with no assessment (checks off, or unknown account) the transfer goes ahead and no decision is written"() {
        when:
        def result = service.transfer(command)

        then:
        1 * risk.assess(command) >> Optional.empty()
        0 * riskRepo.insertDecision(_)
        1 * transfers.insert({ it.status() == TransferStatus.COMPLETED })
        result.risk() == null
    }

    def "the velocity limit is checked before the risk engine is asked"() {
        when:
        service.transfer(command)

        then:
        1 * limiter.acquire(from.id())

        then:
        1 * risk.assess(command) >> Optional.empty()
    }

    def "a replay returns the stored decision and does not assess again"() {
        given:
        def stored = new Transfer(UUID.randomUUID(), TransferKind.TRANSFER, from.id(), to.id(), usd('25.00'), usd('25.00'), null, clock.instant(), TransferStatus.PENDING)
        def decision = new RiskDecision(RiskAction.HOLD, [], 5)

        when:
        def result = service.transfer(command)

        then:
        1 * cache.get('key-1') >> Optional.of(new CachedKey(command.fingerprint(), stored.id()))
        1 * idempotency.find('key-1') >> Optional.of(new IdempotencyRecord('key-1', command.fingerprint(), stored.id()))
        1 * transfers.find(stored.id()) >> Optional.of(stored)
        1 * riskRepo.findDecision(stored.id()) >> Optional.of(decision)
        0 * risk.assess(_)
        result.replayed()
        result.risk() == decision
    }

    def "stepUpConfirmed is part of committed request identity"() {
        expect:
        new TransferService.TransferCommand('k', from.id(), to.id(), usd('1.00'), true).fingerprint() !=
                new TransferService.TransferCommand('k', from.id(), to.id(), usd('1.00'), false).fingerprint()
    }

    def "riskOf looks the decision up by transfer id"() {
        given:
        def id = UUID.randomUUID()

        when:
        def found = service.riskOf(id)

        then:
        1 * riskRepo.findDecision(id) >> Optional.empty()
        found.isEmpty()
    }
}
