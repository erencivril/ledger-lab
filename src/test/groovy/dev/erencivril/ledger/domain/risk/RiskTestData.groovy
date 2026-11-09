package dev.erencivril.ledger.domain.risk

import dev.erencivril.ledger.domain.Currency
import dev.erencivril.ledger.domain.Money

import java.time.Duration

/** Builds a RiskContext with sensible quiet defaults, so a spec only states what it cares about. */
class RiskTestData {

    static RiskContext context(Map overrides = [:]) {
        Map v = [
                amountUsd          : '100.00',
                firstPayment       : false,
                payeeAgeHours      : 24 * 365,
                historyCount       : 0,
                averageUsd         : null,
                stepUpConfirmed    : false,
        ] + overrides
        def usd = new BigDecimal(v.amountUsd as String)
        new RiskContext(
                new Money(usd, Currency.USD),
                usd,
                v.firstPayment as boolean,
                Duration.ofMinutes(((v.payeeAgeHours as BigDecimal) * 60) as long),
                v.historyCount as int,
                v.averageUsd == null ? null : new BigDecimal(v.averageUsd as String),
                v.stepUpConfirmed as boolean)
    }

    static RuleConfig config(String id, RiskAction action, Map<String, Object> params = [:], boolean enabled = true) {
        new RuleConfig(id, action, enabled, params.collectEntries { k, val -> [(k): new BigDecimal(val.toString())] })
    }
}
