# 0001. Money is a BigDecimal at the currency's scale

Status: accepted

## Context

Payments code fails quietly when amounts are floating point or when rounding happens in many places. Currencies also differ: USD has 2 decimals, JPY has 0, KWD has 3.

## Decision

- `Money` is a record of a `BigDecimal` and a `Currency`. The amount is always held at the currency's own scale.
- The constructor never rounds. `1.005 USD` is rejected with `invalid_amount`, because choosing a cent silently is how money goes missing.
- Rounding happens in one place, FX conversion, with `HALF_EVEN`, so long series of conversions do not drift in one direction.
- Amounts travel as JSON strings and go straight into `BigDecimal`. No `double` anywhere.
- The database columns are `numeric(30, 3)`. The scale of the currency is enforced in Java, not in the column.

## Consequences

- Clients must send exact amounts. A client that computes `0.1 + 0.2` and sends it gets a 400, not a guess.
- Adding a currency means adding it to the `Currency` enum with its scale. There is no runtime currency table.
- Comparing amounts uses `compareTo`, never `equals` on `BigDecimal`, because `10.0` and `10.00` are not `equals`. `Money.equals` handles that.

## Evidence

- [`MoneySpec`](../../src/test/groovy/dev/erencivril/ledger/domain/MoneySpec.groovy): scale per currency, rejection of extra decimals, `HALF_EVEN` at the exact half-way cases, sums of many small amounts that drift with doubles and do not here.
- [`FxRateSpec`](../../src/test/groovy/dev/erencivril/ledger/domain/FxRateSpec.groovy) and [`TransferPlannerSpec`](../../src/test/groovy/dev/erencivril/ledger/domain/TransferPlannerSpec.groovy): conversion and the "converts to zero" rejection.
- PIT runs on the `domain` package, where `Money` lives. See [../../README.md](../../README.md).
