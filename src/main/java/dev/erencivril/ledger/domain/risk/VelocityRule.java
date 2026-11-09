package dev.erencivril.ledger.domain.risk;

import java.math.BigDecimal;
import java.util.Optional;

/** One count-or-amount check for either the one-hour or the 24-hour window. */
public record VelocityRule(RiskAction action, int hours, int maxCount, BigDecimal maxAmountUsd) implements Rule {
    public VelocityRule {
        if (hours != 1 && hours != 24) throw new IllegalArgumentException("velocity window must be 1 or 24 hours");
    }
    public String id() { return "VELOCITY_" + hours + "H"; }
    public Optional<RuleHit> evaluate(RiskContext context) {
        VelocitySnapshot v = context.velocity();
        if (!v.available()) return Optional.empty();
        int count = hours == 1 ? v.count1h() : v.count24h();
        BigDecimal amount = hours == 1 ? v.amount1h() : v.amount24h();
        if (count >= maxCount || amount.compareTo(maxAmountUsd) >= 0) {
            return Optional.of(new RuleHit(id(), action, count + " attempts, " + amount.toPlainString() + " USD in " + hours + "h"));
        }
        return Optional.empty();
    }
}
