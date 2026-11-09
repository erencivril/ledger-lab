package dev.erencivril.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/** One side of a double-entry posting. Negative amounts are debits, positive amounts are credits. */
public record JournalLine(UUID accountId, Money amount) {

    public JournalLine {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new IllegalArgumentException("A journal line cannot be zero");
        }
    }
}
