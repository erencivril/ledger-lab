package dev.erencivril.ledger.application

import dev.erencivril.ledger.domain.*

import java.time.Instant

import static dev.erencivril.ledger.domain.Currency.USD

/** Small builders shared by the three strategy specs. */
class StrategySupport {

    static final UUID LOW = UUID.fromString('00000000-0000-0000-0000-000000000001')
    static final UUID HIGH = UUID.fromString('00000000-0000-0000-0000-000000000002')

    static Money usd(String amount) { new Money(new BigDecimal(amount), USD) }

    static Account account(UUID id, String balance) {
        new Account(id, 'o', USD, AccountKind.CUSTOMER, usd(balance), Instant.EPOCH)
    }

    static TransferPlanner planner() {
        new TransferPlanner(new FxRateTable([]), new TreasuryAccounts([(USD): UUID.randomUUID()]))
    }
}
