package dev.erencivril.ledger.domain.risk;

import dev.erencivril.ledger.domain.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Everything the rules may look at for one payment, already collected. The engine never reads a database or
 * Redis: the application layer gathers the facts and hands them over, which keeps the rules pure.
 *
 * @param amount what the sender wants to send, in the sender's currency
 * @param amountUsd the same amount in USD with the fixed rate table, so one threshold fits every currency
 * @param firstPaymentToPayee true when the sender has never completed a payment to this payee
 * @param payeeAccountAge how long ago the payee account was opened
 * @param senderHistoryCount how many completed payments the sender has made recently
 * @param senderAverageUsd the average size of those payments in USD, or null when there are none
 * @param stepUpConfirmed true when the customer already passed the extra check for this payment
 */
public record RiskContext(
        Money amount,
        BigDecimal amountUsd,
        boolean firstPaymentToPayee,
        Duration payeeAccountAge,
        int senderHistoryCount,
        BigDecimal senderAverageUsd,
        boolean stepUpConfirmed,
        NameMatcher.Result nameCheck,
        boolean sanctioned,
        VelocitySnapshot velocity) {

    public RiskContext(Money amount, BigDecimal amountUsd, boolean firstPaymentToPayee,
            Duration payeeAccountAge, int senderHistoryCount, BigDecimal senderAverageUsd, boolean stepUpConfirmed) {
        this(amount, amountUsd, firstPaymentToPayee, payeeAccountAge, senderHistoryCount, senderAverageUsd,
                stepUpConfirmed, new NameMatcher.Result(NameMatcher.Grade.NOT_PROVIDED, 0), false, VelocitySnapshot.empty());
    }

    public RiskContext {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(amountUsd, "amountUsd");
        Objects.requireNonNull(payeeAccountAge, "payeeAccountAge");
        if (senderHistoryCount < 0) {
            throw new IllegalArgumentException("senderHistoryCount must not be negative");
        }
    }
}
