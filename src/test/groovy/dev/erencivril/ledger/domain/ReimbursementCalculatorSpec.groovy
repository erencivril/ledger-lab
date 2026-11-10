package dev.erencivril.ledger.domain

import spock.lang.Specification

import static dev.erencivril.ledger.domain.ReimbursementCalculator.Eligibility.*

class ReimbursementCalculatorSpec extends Specification {
    def calculator = new ReimbursementCalculator()

    def "eligible claim #loss with excess #excess and vulnerability #vulnerable returns #paid"() {
        when:
        def result = calculator.calculate(new BigDecimal(loss), ELIGIBLE, vulnerable, new BigDecimal(excess))

        then:
        result.reimbursement() == new BigDecimal(paid)
        result.appliedExcess() == new BigDecimal(applied)
        result.reimbursement().scale() == 2
        result.appliedExcess().scale() == 2

        where:
        loss       | excess | vulnerable | paid       | applied
        '0.00'     | '0'    | false      | '0.00'     | '0.00'
        '0.01'     | '0'    | false      | '0.01'     | '0.00'
        '99.99'    | '100'  | false      | '0.00'     | '99.99'
        '100'      | '100'  | false      | '0.00'     | '100.00'
        '100.01'   | '100'  | false      | '0.01'     | '100.00'
        '1000'     | '0'    | false      | '1000.00'  | '0.00'
        '1000'     | '25'   | false      | '975.00'   | '25.00'
        '1000'     | '100'  | false      | '900.00'   | '100.00'
        '84999.99' | '0'    | false      | '84999.99' | '0.00'
        '85000'    | '100'  | false      | '84900.00' | '100.00'
        '85000'    | '0'    | false      | '85000.00' | '0.00'
        '85000.01' | '0'    | false      | '85000.00' | '0.00'
        '90000'    | '0'    | false      | '85000.00' | '0.00'
        '1000'     | '100'  | true       | '1000.00'  | '0.00'
        '90000'    | '100'  | true       | '85000.00' | '0.00'
    }

    def "externally established exception #eligibility with vulnerability #vulnerable pays #paid"() {
        when:
        def result = calculator.calculate(1000G, eligibility, vulnerable, 100G)

        then:
        result.reimbursement() == paid
        result.appliedExcess() == 0.00G
        result.split().isEmpty()

        where:
        eligibility      | vulnerable | paid
        OUT_OF_SCOPE     | false      | 0.00G
        OUT_OF_SCOPE     | true       | 0.00G
        FRAUDULENT       | false      | 0.00G
        FRAUDULENT       | true       | 0.00G
        GROSS_NEGLIGENCE | false      | 0.00G
    }

    def "gross negligence cannot defeat a vulnerable eligible consumer's claim"() {
        when:
        def result = calculator.calculate(1000G, GROSS_NEGLIGENCE, true, 100G)

        then:
        result.reimbursement() == 1000.00G
        result.appliedExcess() == 0.00G
        result.split().isEmpty()
    }

    def "single receiving PSP split with maximum excess preserves every penny"() {
        when:
        def result = calculator.calculate(new BigDecimal(loss), ELIGIBLE, false, 100G)
        def split = result.split().orElseThrow()

        then:
        split.sendingPsp() == new BigDecimal(sender)
        split.receivingPsp() == new BigDecimal(receiver)
        split.sendingPsp() + split.receivingPsp() == result.reimbursement()
        split.sendingPsp().scale() == 2
        split.receivingPsp().scale() == 2

        where:
        loss     | sender     | receiver
        '99.99'  | '0.00'     | '0.00'
        '100.01' | '0.01'     | '0.00'
        '100.02' | '0.01'     | '0.01'
        '100.03' | '0.02'     | '0.01'
        '1000'   | '450.00'   | '450.00'
        '85000'  | '42450.00' | '42450.00'
    }

    def "no settlement estimate for reduced excess or vulnerability"() {
        expect:
        calculator.calculate(1000G, ELIGIBLE, vulnerable, excess).split().isEmpty()

        where:
        vulnerable | excess
        false      | 0G
        false      | 25G
        false      | 99.99G
        true       | 100G
    }

    def "cap plus nonzero effective excess is explicitly unsupported, not guessed"() {
        when:
        calculator.calculate(85000.01G, ELIGIBLE, false, excess)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'Loss above the cap with a nonzero excess is not modelled'

        where:
        excess << [0.01G, 100G]
    }

    def "invalid money input is rejected: #loss, #excess"() {
        when:
        calculator.calculate(loss, ELIGIBLE, false, excess)

        then:
        thrown(IllegalArgumentException)

        where:
        loss    | excess
        -0.01G  | 0G
        1G      | -0.01G
        1G      | 100.01G
        1.001G  | 0G
        1G      | 0.001G
    }

    def "missing input is rejected"() {
        when:
        calculator.calculate(loss, eligibility, false, excess)

        then:
        thrown(NullPointerException)

        where:
        loss | eligibility | excess
        null | ELIGIBLE    | 0G
        1G   | null        | 0G
        1G   | ELIGIBLE    | null
    }
}
