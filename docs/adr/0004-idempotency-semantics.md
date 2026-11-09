# 0004. The idempotency key is claimed in the transaction that moves the money

Status: accepted

## Context

Clients retry. A network timeout after a commit looks, to the client, exactly like a request that never arrived. A retry must not move money twice, and two copies of the same request arriving together must not both win.

## Decision

- `POST /transfers` requires an `Idempotency-Key` header (at most 255 characters).
- Same key, same request: `200` with the original transfer and `Idempotency-Replayed: true`. The first call returns `201`.
- Same key, different request: `409 idempotency_key_reused`. "Same request" means same source, destination, amount and currency, compared by a SHA-256 fingerprint stored next to the key. The amount is normalised first, so `10` and `10.00` match.
- PostgreSQL decides. The key is claimed with `INSERT ... ON CONFLICT DO NOTHING` on the primary key of `idempotency_keys`, inside the same transaction that moves the money. If two requests with the same key arrive together, the second insert waits for the first transaction, then sees the conflict and returns the stored result. If the first rolls back, the second proceeds.
- A rejected transfer (insufficient funds, unknown account) rolls back its claim, so the same key can be used again after the client fixes the request. Failures are not stored.
- When a transfer is run again after a lost race, the claim is part of what is rolled back and redone. A failed attempt never leaves a claim behind.
- Keys are global and never expire. That is a known limitation, not a design goal.

## Consequences

- There is no state where the key exists and the money did not move, because both commit together.
- A client that gets `409 concurrent_update` or `503` can repeat the same request with the same key without risk.
- A rejected request is not remembered. A client that retries a request that was rightly rejected gets the rejection again, computed fresh.

## Evidence

- [`TransferServiceIntegrationSpec`](../../src/integrationTest/groovy/dev/erencivril/ledger/TransferServiceIntegrationSpec.groovy): "retrying with the same key and the same body returns the original transfer and moves money once", "the same key with a different body is a conflict", "twenty parallel requests with the same key create exactly one transfer", "a rejected transfer changes nothing and does not consume the idempotency key".
- [`FailureModesIntegrationSpec`](../../src/integrationTest/groovy/dev/erencivril/ledger/FailureModesIntegrationSpec.groovy): crash before commit, response lost after commit, database outage and the same key afterwards.
- [`TransferServiceSpec`](../../src/test/groovy/dev/erencivril/ledger/application/TransferServiceSpec.groovy): the order of calls, the fingerprint, and the retry loop.
