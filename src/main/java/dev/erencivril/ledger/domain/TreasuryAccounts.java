package dev.erencivril.ledger.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** The id of the system account for each currency. */
public final class TreasuryAccounts {

    private final Map<Currency, UUID> ids;

    public TreasuryAccounts(Map<Currency, UUID> ids) {
        this.ids = new EnumMap<>(Currency.class);
        this.ids.putAll(ids);
    }

    public UUID idFor(Currency currency) {
        UUID id = ids.get(currency);
        if (id == null) {
            throw new IllegalStateException("No treasury account for " + currency);
        }
        return id;
    }
}
