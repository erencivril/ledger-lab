package dev.erencivril.ledger.domain.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Fires when the amount is at least {@code factor} times the sender's usual payment. It needs at least
 * {@code minHistory} earlier payments to know what usual means; with less history it stays quiet instead
 * of guessing.
 */
public record AmountDeviationRule(RiskAction action, BigDecimal factor, int minHistory) implements Rule {

    public static final String ID = "AMOUNT_DEVIATION";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<RuleHit> evaluate(RiskContext context) {
        BigDecimal average = context.senderAverageUsd();
        if (context.senderHistoryCount() < minHistory || average == null || average.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal ratio = context.amountUsd().divide(average, 1, RoundingMode.HALF_EVEN);
        if (context.amountUsd().compareTo(average.multiply(factor)) >= 0) {
            return Optional.of(new RuleHit(
                    ID,
                    action,
                    "amount is " + ratio.toPlainString() + " times the sender's average of "
                            + average.toPlainString() + " USD"));
        }
        return Optional.empty();
    }
}
