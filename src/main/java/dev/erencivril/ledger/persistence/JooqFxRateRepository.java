package dev.erencivril.ledger.persistence;

import static dev.erencivril.ledger.persistence.jooq.Tables.FX_RATES;

import dev.erencivril.ledger.application.Ports.FxRateRepository;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.FxRate;
import java.util.List;
import org.jooq.DSLContext;

final class JooqFxRateRepository implements FxRateRepository {

    private final DSLContext dsl;

    JooqFxRateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<FxRate> findAll() {
        return dsl.selectFrom(FX_RATES)
                .fetch(row -> new FxRate(
                        Currency.parse(row.getFromCurrency()), Currency.parse(row.getToCurrency()), row.getRate()));
    }
}
