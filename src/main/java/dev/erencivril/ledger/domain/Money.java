package dev.erencivril.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount in one currency, always held at that currency's scale (2 decimals for USD, 0 for JPY).
 *
 * <p>The constructor never rounds silently: an input with more decimals than the currency allows
 * is rejected. Use {@link #rounded(BigDecimal, Currency)} where rounding is the intended behaviour,
 * such as an FX conversion. Rounding is always HALF_EVEN (banker's rounding).
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        try {
            amount = amount.setScale(currency.minorUnits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new DomainException.InvalidAmount(
                    currency + " allows at most " + currency.minorUnits() + " decimal places, got " + amount.toPlainString());
        }
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /** Rounds to the currency's scale using HALF_EVEN. */
    public static Money rounded(BigDecimal amount, Currency currency) {
        return new Money(amount.setScale(currency.minorUnits(), ROUNDING), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (currency != other.currency) {
            throw new DomainException.CurrencyMismatch("Cannot combine " + currency + " with " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
