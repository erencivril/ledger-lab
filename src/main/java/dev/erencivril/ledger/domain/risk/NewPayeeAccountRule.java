package dev.erencivril.ledger.domain.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Fires when the payee account is younger than {@code maxAge} and the amount is at least
 * {@code minAmountUsd}. Accounts opened a few hours before they receive money are a common sign of a mule
 * or a scam account; on its own it is weak, because people also open accounts to be paid.
 */
public record NewPayeeAccountRule(RiskAction action, Duration maxAge, BigDecimal minAmountUsd) implements Rule {

    public static final String ID = "NEW_PAYEE_ACCOUNT";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<RuleHit> evaluate(RiskContext context) {
        if (context.payeeAccountAge().compareTo(maxAge) < 0 && context.amountUsd().compareTo(minAmountUsd) >= 0) {
            return Optional.of(new RuleHit(
                    ID,
                    action,
                    "payee account is " + context.payeeAccountAge().toMinutes() + " minutes old, amount "
                            + context.amountUsd().toPlainString() + " USD"));
        }
        return Optional.empty();
    }
}
