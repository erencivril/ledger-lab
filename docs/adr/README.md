# Architecture decision records

Short notes on the choices that shape the code: what was decided, why, what it costs, and which test or file shows it.

| ADR | Decision |
|---|---|
| [0001](0001-money-representation.md) | Money is a `BigDecimal` at the currency's scale, rounded in one place only |
| [0002](0002-double-entry-journal.md) | Every transfer is a balanced double-entry journal, checked in Java and again by PostgreSQL |
| [0003](0003-lock-strategy.md) | Three locking strategies behind one interface; pessimistic is the default |
| [0004](0004-idempotency-semantics.md) | The idempotency key is claimed in the same transaction that moves the money |
| [0005](0005-redis-trust-boundary.md) | Redis is never trusted for correctness |
| [0006](0006-sparkjava-behind-a-thin-routing-layer.md) | SparkJava stays, behind a thin routing layer, with a known exit path |
| [0007](0007-outbox-instead-of-saga.md) | A transactional outbox, not a saga, for anything that must follow a transfer |
| [0008](0008-risk-engine-in-the-domain.md) | The risk engine is pure domain code and its rules are versioned data |
| [0009](0009-holds-use-a-hold-account.md) | A held transfer moves the money into a hold account |

| [0010](0010-poll-the-transactional-outbox.md) | Poll a transactional outbox for risk features |

Each file has the same sections: Status, Context, Decision, Consequences, Evidence.
