# 0010. Poll a transactional outbox for risk features

Status: accepted.

## Context

Risk history and known payees are a read model. Writing them after a payment commits would leave a gap if the process crashed. Publishing to a broker would have the same gap without an outbox and adds another service to operate.

## Decision

A database trigger appends one `risk_outbox` event when a payment first becomes COMPLETED, in the same transaction as the journal and balances. It captures sender, payee, source amount, USD amount and completion time. Holds emit only on release, never on rejection. The migration also queues existing completed payments.

A worker polls every 250ms with `FOR UPDATE SKIP LOCKED`. It inserts the payment into `risk_features_payments`, updates per-sender and sender/payee aggregates, then marks the event processed. All three writes share one transaction. The payment id is the deduplication key: redelivery does not increment aggregates again. This is at-least-once delivery with idempotent projection, not an exactly-once transport claim. Multiple pollers skip each other's locked events. A failed batch rolls back and is retried on the next tick, including a deadlock between aggregate updates.

History queries union projected payments with completed ledger payments not yet projected, in one SQL snapshot. The known-payee query also falls back to completed transfers. A stalled poller therefore does not remove facts from risk decisions. The aggregate tables are inspectable summaries, not balances.

## Consequences

No Kafka. The outbox and consumed payment rows need retention planning in a real service. There is no automated pruning here. A poison event keeps retrying and is logged; there is no dead-letter queue. A process crash between feature writes and acknowledgement rolls the whole projection back. Ledger correctness does not depend on projection freshness.

## Evidence

`OutboxIntegrationSpec`: committed and rolled-back events, two workers, redelivery, hold/release/reject, stalled projection, a projection failure after the deduplication insert, and a locked pending row that another poller skips.
