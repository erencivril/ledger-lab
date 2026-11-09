package dev.erencivril.ledger.application

import dev.erencivril.ledger.application.Ports.*
import dev.erencivril.ledger.domain.*
import dev.erencivril.ledger.domain.risk.*
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Function

import static dev.erencivril.ledger.domain.Currency.*

class LedgerRiskAssessorSpec extends Specification {

    def now = Instant.parse('2026-03-01T10:00:00Z')
    def clock = Clock.fixed(now, ZoneOffset.UTC)
    def riskRepo = Mock(RiskRepository)
    def tx = Stub(LedgerTransaction) { risk() >> riskRepo }
    def unitOfWork = Mock(UnitOfWork)
    def rates = new FxRateTable([new FxRate(EUR, USD, new BigDecimal('1.10'))])
    RiskContext seen
    def realEngine = new RiskEngine(8, [new Rule() {
        String id() { 'SPY' }
        Optional<RuleHit> evaluate(RiskContext c) { seen = c; Optional.empty() }
    }], 0, 0)
    def assessor = new LedgerRiskAssessor(unitOfWork, realEngine, rates, clock)

    def from = UUID.randomUUID()
    def to = UUID.randomUUID()

    def setup() {
        unitOfWork.execute(_) >> { Function f -> f.apply(tx) }
    }

    static Money eur(String a) { new Money(new BigDecimal(a), EUR) }

    def "it builds the context from ledger facts: first payment, payee age, history, USD amounts"() {
        when:
        def result = assessor.assess(new TransferService.TransferCommand('k', from, to, eur('100.00'), true))

        then:
        1 * riskRepo.payeeFacts(from, to) >> Optional.of(new PayeeFacts('Ayse Yilmaz', now.minusSeconds(7200), false))
        1 * riskRepo.senderHistory(from, now.minus(java.time.Duration.ofDays(90))) >> new SenderHistory(4, new BigDecimal('50.000'))
        result.isPresent()
        result.get().decision().ruleSetVersion() == 8
        seen.amount() == eur('100.00')
        seen.amountUsd() == new BigDecimal('110.00')
        seen.firstPaymentToPayee()
        seen.payeeAccountAge() == java.time.Duration.ofHours(2)
        seen.senderHistoryCount() == 4
        seen.senderAverageUsd() == new BigDecimal('55.00')
        seen.stepUpConfirmed()
    }

    def "a payee that was paid before is not a first payment; no history means no average"() {
        when:
        assessor.assess(new TransferService.TransferCommand('k', from, to, eur('1.00')))

        then:
        1 * riskRepo.payeeFacts(from, to) >> Optional.of(new PayeeFacts('x', now.minusSeconds(60), true))
        1 * riskRepo.senderHistory(_, _) >> new SenderHistory(0, null)
        !seen.firstPaymentToPayee()
        seen.senderAverageUsd() == null
        seen.senderHistoryCount() == 0
        !seen.stepUpConfirmed()
    }

    def "a payee opened in the future (clock skew) counts as age zero, not negative"() {
        when:
        assessor.assess(new TransferService.TransferCommand('k', from, to, eur('1.00')))

        then:
        1 * riskRepo.payeeFacts(from, to) >> Optional.of(new PayeeFacts('x', now.plusSeconds(60), true))
        1 * riskRepo.senderHistory(_, _) >> new SenderHistory(0, null)
        seen.payeeAccountAge() == java.time.Duration.ZERO
    }

    def "an unknown payee gives no assessment, so the transfer fails later with a normal 404"() {
        when:
        def result = assessor.assess(new TransferService.TransferCommand('k', from, to, eur('1.00')))

        then:
        1 * riskRepo.payeeFacts(from, to) >> Optional.empty()
        0 * riskRepo.senderHistory(*_)
        result.isEmpty()
    }

    def "the disabled assessor never assesses"() {
        expect:
        RiskAssessor.disabled().assess(new TransferService.TransferCommand('k', from, to, eur('1.00'))).isEmpty()
    }
}
