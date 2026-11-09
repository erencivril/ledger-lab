package dev.erencivril.ledger.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** An immutable lookup of fixed FX rates by currency pair. */
public final class FxRateTable {

    private record Pair(Currency from, Currency to) {}

    private final Map<Pair, FxRate> rates = new HashMap<>();

    public FxRateTable(Collection<FxRate> rates) {
        for (FxRate rate : rates) {
            if (this.rates.put(new Pair(rate.from(), rate.to()), rate) != null) {
                throw new IllegalArgumentException("Duplicate FX rate " + rate.from() + " to " + rate.to());
            }
        }
    }

    public Optional<FxRate> find(Currency from, Currency to) {
        return Optional.ofNullable(rates.get(new Pair(from, to)));
    }

    /** The amount in USD, rounded to cents. A USD amount is returned unchanged. */
    public Money toUsd(Money money) {
        if (money.currency() == Currency.USD) {
            return money;
        }
        FxRate rate = find(money.currency(), Currency.USD)
                .orElseThrow(() -> new DomainException.NoFxRate(money.currency(), Currency.USD));
        return rate.convert(money);
    }

    public int size() {
        return rates.size();
    }
}
