package dev.erencivril.ledger.domain;

import java.util.Arrays;

/**
 * The currencies the ledger supports, with the number of minor-unit digits each one uses
 * (2 for cents, 0 for yen, 3 for Kuwaiti fils).
 */
public enum Currency {
    USD(2),
    EUR(2),
    GBP(2),
    TRY(2),
    JPY(0),
    KWD(3);

    private final int minorUnits;

    Currency(int minorUnits) {
        this.minorUnits = minorUnits;
    }

    /** Number of digits after the decimal point for this currency. */
    public int minorUnits() {
        return minorUnits;
    }

    /** Parses an ISO 4217 code. Case-sensitive on purpose: "usd" is rejected. */
    public static Currency parse(String code) {
        return Arrays.stream(values())
                .filter(c -> c.name().equals(code))
                .findFirst()
                .orElseThrow(() -> new DomainException.UnsupportedCurrency(code));
    }
}
