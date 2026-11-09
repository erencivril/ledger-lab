package dev.erencivril.ledger.domain

import spock.lang.Specification

import static dev.erencivril.ledger.domain.Currency.*

class TreasuryAccountsSpec extends Specification {

    def "returns the account id configured for each currency"() {
        given:
        def usd = UUID.randomUUID()
        def eur = UUID.randomUUID()
        def treasury = new TreasuryAccounts([(USD): usd, (EUR): eur])

        expect:
        treasury.idFor(USD) == usd
        treasury.idFor(EUR) == eur
    }

    def "fails loudly for a currency without a treasury account"() {
        when:
        new TreasuryAccounts([(USD): UUID.randomUUID()]).idFor(EUR)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('EUR')
    }

    def "does not change when the source map changes afterwards"() {
        given:
        def usd = UUID.randomUUID()
        def source = [(USD): usd]
        def treasury = new TreasuryAccounts(source)

        when:
        source[USD] = UUID.randomUUID()

        then:
        treasury.idFor(USD) == usd
    }
}
