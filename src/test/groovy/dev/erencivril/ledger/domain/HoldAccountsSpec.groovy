package dev.erencivril.ledger.domain

import spock.lang.Specification

class HoldAccountsSpec extends Specification {

    def "it returns the id for a currency and fails loudly for one it does not know"() {
        given:
        def id = UUID.randomUUID()
        def holds = new HoldAccounts([(Currency.USD): id])

        expect:
        holds.idFor(Currency.USD) == id

        when:
        holds.idFor(Currency.EUR)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('EUR')
    }

    def "it keeps its own copy of the map"() {
        given:
        def map = [(Currency.USD): UUID.randomUUID()]
        def holds = new HoldAccounts(map)

        when:
        map.clear()

        then:
        holds.idFor(Currency.USD) != null
    }
}
