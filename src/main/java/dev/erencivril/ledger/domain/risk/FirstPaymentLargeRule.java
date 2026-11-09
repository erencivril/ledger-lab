package dev.erencivril.ledger.domain.risk;

import java.math.BigDecimal;
import java.util.Optional;

/** Fires when the sender pays this payee for the first time and the amount is at least {@code minAmountUsd}. */
public record FirstPaymentLargeRule(RiskAction action, BigDecimal minAmountUsd) implements Rule {

    public static final String ID = "FIRST_PAYMENT_LARGE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<RuleHit> evaluate(RiskContext context) {
        if (context.firstPaymentToPayee() && context.amountUsd().compareTo(minAmountUsd) >= 0) {
            return Optional.of(new RuleHit(
                    ID, action, "first payment to this payee, " + context.amountUsd().toPlainString() + " USD"));
        }
        return Optional.empty();
    }
}
