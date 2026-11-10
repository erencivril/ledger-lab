# 0007. A transactional outbox, not a saga, for anything that must follow a transfer

Status: accepted. Implemented for the local risk read model; external delivery remains out of scope.

## Context

Sooner or later something outside the ledger has to learn that a transfer happened: a notification, a statement cache, a feed for other services. Calling those from the request would make the transfer depend on them, and a failure half-way would leave the two sides disagreeing. There are two usual answers: a saga (a chain of steps across services, each with a compensating step) or an outbox (write the fact to be published in the same database transaction, and publish it afterwards).

## Decision

Use a transactional outbox.

- In the same transaction that writes the transfer and the journal, insert a row into an `outbox` table describing what happened.
- A separate poller reads unsent rows in order and delivers them, marking each as sent. Delivery is at least once, so consumers must tolerate duplicates, which they can do by using the transfer id as the key.
- No message broker is introduced for this. A polling reader on PostgreSQL is enough at this size.

A saga is not needed because there is no multi-service transaction to coordinate. The ledger is one database, one transaction, and it already gives all-or-nothing for the money. What other parts need is a reliable "this happened" signal, which is exactly what an outbox provides.

## Consequences

- The ledger commit never depends on a consumer being up.
- Consumers can lag, and can see an event more than once. That is stated in the consumer's contract.
- If the ledger ever spans several databases, a transfer between them would need a different design (a transfer state machine or a saga). That is a known limit of the current single-PostgreSQL design, listed in the README.

## Evidence

Implemented for the local risk read model by migration V12 and `RiskFeatureProjector`; see [ADR 0010](0010-poll-the-transactional-outbox.md) and `OutboxIntegrationSpec`. Projection, deduplication and acknowledgement commit together. External delivery, backlog monitoring and retention remain outside this learning example.
