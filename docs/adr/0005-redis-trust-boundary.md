# 0005. Redis is never trusted for correctness

Status: accepted

## Context

Redis is fast and convenient, and it can be empty, stale, wrong, or down. Money must not depend on it.

## Decision

Redis does two jobs, and neither affects whether money is right.

1. **Velocity limit.** A sliding window per source account, in a sorted set updated by one Lua script, so the check and the update are atomic across application instances. The script uses the Redis clock. It is a risk control, not an integrity control. If Redis is down the limiter lets the transfer through and logs a warning (`VELOCITY_FAIL_OPEN=true`, the default). `VELOCITY_FAIL_OPEN=false` refuses the transfer instead.
2. **Idempotency cache.** After a transfer commits, `idem:<key>` is written with a 24 hour expiry as a hint. Every replay loads the canonical PostgreSQL key binding and compares the request fingerprint there. Redis hash mismatches do not decide conflicts, and neither a valid unrelated transfer ID nor a forged unclaimed key can substitute another result. The canonical lookup is deliberately never skipped. Nothing is created or moved because of what Redis says.

The rule behind both: Redis may make a request cheaper or may reject one early. It may never make a request succeed, and never decide which transfer a key refers to.

## Consequences

- Losing Redis, or flushing it, costs speed and the velocity limit, never money.
- The limiter fails open by default. A deployment that treats the limit as a hard control must set `VELOCITY_FAIL_OPEN=false` and accept refused transfers during a Redis outage.
- `/health` reports Redis as `degraded` and stays `200`, because transfers still work.

## Evidence

- [`RedisBehaviourIntegrationSpec`](../../src/integrationTest/groovy/dev/erencivril/ledger/RedisBehaviourIntegrationSpec.groovy): "a replay is served even if the Redis cache entry is gone", "a poisoned cache entry cannot make the API repeat or invent a transfer", "garbage in the cache is ignored", "with Redis unreachable, transfers still work, replays still dedupe via Postgres and health is degraded", "with Redis unreachable and fail-open disabled, transfers are refused instead of unchecked".
- Window behaviour: "the limiter allows N acquisitions per window", "the window slides", "under 60 parallel callers exactly N are admitted".
