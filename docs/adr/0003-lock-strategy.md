# 0003. Three locking strategies behind one interface, pessimistic by default

Status: accepted

## Context

Two transfers that touch the same account at the same time must not both spend the same money, and must not deadlock. There are three common ways to get that from PostgreSQL. They behave differently under load, so the project implements all three behind one small interface, `TransferStrategy`, and runs the same invariant suite against each.

Everything else about a transfer (idempotency key, transfer row, journal, retry loop) is identical for all three. Only "how do the two balances get changed safely" differs.

## The three strategies

| | Pessimistic (default) | Optimistic | Atomic conditional UPDATE |
|---|---|---|---|
| Class | `PessimisticLockStrategy` | `OptimisticVersionStrategy` | `AtomicUpdateStrategy` |
| Idea | Lock the rows first, then read and write | Read freely, write only if nothing changed | Let one UPDATE both check and change the balance |
| SQL | `SELECT ... FOR UPDATE` on both rows, then `UPDATE` | `SELECT`, then `UPDATE ... WHERE id = ? AND version = ?` | `UPDATE ... SET balance = balance - ? WHERE id = ? AND balance >= ?` |
| Waits | Yes, on the row lock, from the first SELECT to commit | No while reading. The UPDATE still takes a row lock until commit | Yes, but only from the UPDATE to commit |
| Retries | Never, the lock prevents conflicts | Yes, when the version check fails | Never |
| Where the funds check happens | In Java, on locked data | In Java, on data that the version check later confirms | In SQL, at the moment of the write |
| Good at | Hot accounts, predictable behaviour, easy to explain | Many accounts, rarely the same one twice, long think-time between read and write | Simple debits and credits where the only rule is "not below zero" |
| Bad at | Many transfers sharing one account line up | A hot account: most attempts lose and redo their work, and abort when the retry budget runs out | Rules that need to read more than the balance; the plan is built from a possibly stale read |

All three touch the two rows in ascending id order, so two transfers in opposite directions cannot deadlock.

## Decision

- Pessimistic locking is the production default. It is the one whose behaviour does not depend on the workload: a conflict always means "wait", never "fail and redo".
- The other two are selected with `LOCK_STRATEGY=optimistic` or `atomic`. An unknown value stops the application from starting.
- The optimistic strategy raises `ConcurrentUpdate`. `TransferService` rolls back and runs the whole transaction again, including the key claim, up to `TRANSFER_MAX_ATTEMPTS` (default 5), with full jitter between attempts. After the last attempt the client gets `409 concurrent_update` with `Retry-After`, and it is safe to repeat the request with the same key.
- PostgreSQL's own aborts (deadlock `40P01`, serialization failure `40001`) are translated to the same `ConcurrentUpdate`, so one retry loop covers all of them.

## When each one wins

The measured numbers are in [../benchmarks.md](../benchmarks.md), with the hardware. Read them as a comparison on one small machine, not as absolute capacity.

- Many accounts, few collisions: optimistic and atomic avoid holding locks while Java works, pessimistic pays a round trip to lock first. Differences are small.
- One hot account: pessimistic and atomic queue behind the row lock and keep going. Optimistic wastes work and, with a small retry budget, rejects requests that pessimistic would have served.
- The atomic variant moves the "enough money?" decision into SQL. That is the shortest critical section of the three, but it only works when that is the whole rule.

## Consequences

- Three implementations are three things to understand and keep correct. The shared invariant suite is what keeps that honest.
- The `version` column (migration `V4`) is bumped by every strategy, so it always means "how many times this balance changed".
- Moving off PostgreSQL row locks (for example to sharded accounts) would invalidate the pessimistic and atomic strategies; the interface stays, the implementations change.

## Evidence

- Unit specs per strategy, with mocked repositories: [`PessimisticLockStrategySpec`](../../src/test/groovy/dev/erencivril/ledger/application/PessimisticLockStrategySpec.groovy), [`OptimisticVersionStrategySpec`](../../src/test/groovy/dev/erencivril/ledger/application/OptimisticVersionStrategySpec.groovy), [`AtomicUpdateStrategySpec`](../../src/test/groovy/dev/erencivril/ledger/application/AtomicUpdateStrategySpec.groovy), [`RetryPolicySpec`](../../src/test/groovy/dev/erencivril/ledger/application/RetryPolicySpec.groovy).
- The same stress and invariant suite per strategy: [`StrategyStressIntegrationSpec`](../../src/integrationTest/groovy/dev/erencivril/ledger/StrategyStressIntegrationSpec.groovy).
- Deterministic lost-race tests: [`StrategyBehaviourIntegrationSpec`](../../src/integrationTest/groovy/dev/erencivril/ledger/StrategyBehaviourIntegrationSpec.groovy).
- The benchmark: `./gradlew benchmark`, results in [../benchmarks.md](../benchmarks.md).
