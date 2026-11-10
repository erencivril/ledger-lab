package dev.erencivril.ledger.application

import dev.erencivril.ledger.domain.Money
import dev.erencivril.ledger.domain.Currency
import spock.lang.Specification

class RequestIdentityRegressionSpec extends Specification {
    def from = UUID.randomUUID()
    def to = UUID.randomUUID()
    def fingerprint(String name, boolean confirmed = false, String amount = '1.00') {
        new TransferService.TransferCommand('key', from, to,
                new Money(new BigDecimal(amount), Currency.USD), confirmed, name).fingerprint()
    }

    def "name identity distinguishes exact optional values #left and #right"() {
        expect:
        fingerprint(left) != fingerprint(right)

        where:
        left   | right
        null   | ''
        ''     | ' '
        'A|B'  | 'A:B'
        'Alice'| 'Bob'
        'ALICE'| 'Alice'
    }

    def "command rejects malformed UTF-16 before it can be fingerprinted"() {
        when:
        new TransferService.TransferCommand('key', from, to,
                new Money(1G, Currency.USD), false, name)

        then:
        def error = thrown(dev.erencivril.ledger.domain.DomainException)
        error.code() == 'invalid_text'

        where:
        name << [String.valueOf((char) 0xd800), String.valueOf((char) 0xdc00),
                 'Alice' + (char) 0xd800 + 'Smith', 'Alice' + (char) 0xdc00 + 'Smith']
    }

    def "equivalent decimal amounts replay with identical names and confirmation"() {
        expect:
        fingerprint('A:|', true, '1') == fingerprint('A:|', true, '1.000')
        fingerprint(null, true) != fingerprint(null, false)
    }
}
