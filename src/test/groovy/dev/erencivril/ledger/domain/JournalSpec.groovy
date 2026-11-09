package dev.erencivril.ledger.domain

import spock.lang.Specification

import static dev.erencivril.ledger.domain.Currency.*

class JournalSpec extends Specification {

    static Money money(String amount, Currency currency = USD) {
        new Money(new BigDecimal(amount), currency)
    }

    static JournalLine line(String amount, Currency currency = USD) {
        new JournalLine(UUID.randomUUID(), money(amount, currency))
    }

    def "a journal that sums to zero in every currency is accepted"() {
        when:
        def journal = new Journal(lines)

        then:
        journal.lines().size() == lines.size()

        where:
        lines << [
                [line('-10.00'), line('10.00')],
                [line('-10.00'), line('4.00'), line('6.00')],
                [line('-10.00'), line('10.00'), line('-92.00', EUR), line('92.00', EUR)],
                [line('-1000', JPY), line('1000', JPY)],
        ]
    }

    def "a journal that does not sum to zero is rejected: #desc"() {
        when:
        new Journal(lines)

        then:
        def e = thrown(DomainException.UnbalancedJournal)
        e.code() == 'unbalanced_journal'
        e.message.contains(currency)

        where:
        desc                    | currency | lines
        'one cent short'        | 'USD'    | [line('-10.00'), line('9.99')]
        'one cent over'         | 'USD'    | [line('-10.00'), line('10.01')]
        'only credits'          | 'USD'    | [line('10.00')]
        'second currency off'   | 'EUR'    | [line('-10.00'), line('10.00'), line('-92.00', EUR), line('91.99', EUR)]
        'currencies not netted' | 'USD'    | [line('-10.00'), line('10.00', EUR)]
    }

    def "amounts in different currencies never offset each other"() {
        when:
        new Journal([line('-10.00', USD), line('10.00', EUR)])

        then:
        thrown(DomainException.UnbalancedJournal)
    }

    def "an empty journal is rejected"() {
        when:
        new Journal([])

        then:
        thrown(IllegalArgumentException)
    }

    def "a zero line is rejected"() {
        when:
        line('0.00')

        then:
        thrown(IllegalArgumentException)
    }

    def "the journal keeps its own copy of the lines"() {
        given:
        def input = [line('-1.00'), line('1.00')]
        def journal = new Journal(input)

        when:
        input.clear()

        then:
        journal.lines().size() == 2

        when:
        journal.lines().add(line('1.00'))

        then:
        thrown(UnsupportedOperationException)
    }
}
