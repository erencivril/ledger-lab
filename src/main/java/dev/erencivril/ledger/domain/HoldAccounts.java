package dev.erencivril.ledger.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** The id of the hold account for each currency. Money of a held transfer sits here until it is released or rejected. */
public final class HoldAccounts {

    private final Map<Currency, UUID> ids;

    public HoldAccounts(Map<Currency, UUID> ids) {
        this.ids = new EnumMap<>(Currency.class);
        this.ids.putAll(ids);
    }

    public UUID idFor(Currency currency) {
        UUID id = ids.get(currency);
        if (id == null) {
            throw new IllegalStateException("No hold account for " + currency);
        }
        return id;
    }
}
