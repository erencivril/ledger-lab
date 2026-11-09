package dev.erencivril.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** A fixed exchange rate: one unit of {@code from} is worth {@code rate} units of {@code to}. */
public record FxRate(Currency from, Currency to, BigDecimal rate) {

    public FxRate {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(rate, "rate");
        if (from == to) {
            throw new IllegalArgumentException("An FX rate needs two different currencies");
        }
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("FX rate must be positive");
        }
    }

    /** Converts to the target currency, rounding HALF_EVEN to that currency's scale. */
    public Money convert(Money money) {
        if (money.currency() != from) {
            throw new DomainException.CurrencyMismatch(
                    "Rate " + from + " to " + to + " cannot convert " + money.currency());
        }
        return Money.rounded(money.amount().multiply(rate), to);
    }
}
