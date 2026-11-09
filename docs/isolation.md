# Isolation levels, shown on PostgreSQL

The specs in [`IsolationIntegrationSpec`](../src/integrationTest/groovy/dev/erencivril/ledger/IsolationIntegrationSpec.groovy) run against a real PostgreSQL 16. They use their own small table (`lab_accounts`) and not the ledger tables, because they write balances without journal entries on purpose.

## How the experiments are made repeatable

An anomaly needs a particular order of events. Hoping that two threads happen to interleave that way gives a test that passes on some days. So each experiment opens two connections by hand and drives them from one thread, in a fixed order: connection 1 reads, connection 2 reads, connection 1 writes and commits, connection 2 writes. There are no sleeps. The result is the same on every run.

Where one session has to block on another (a `SELECT ... FOR UPDATE` that waits), a second thread is used and the test asks PostgreSQL itself, through `pg_stat_activity`, whether the session is waiting for a lock. It does not guess how long that takes.

The one experiment that is about timing, the retry loop, uses real threads and real jitter. Its outcome does not depend on timing: the end state is fixed by the rule, whatever the order.

PostgreSQL has three isolation levels in practice. `READ UNCOMMITTED` is treated as `READ COMMITTED`.

| Level | What a statement sees | What it prevents | What it allows |
|---|---|---|---|
| READ COMMITTED (default) | Every statement sees what was committed when that statement began | Dirty reads | Lost update with read-modify-write, write skew, non-repeatable reads |
| REPEATABLE READ | One snapshot for the whole transaction | The above, plus lost update (by aborting) | Write skew |
| SERIALIZABLE | Same snapshot, plus tracking of read/write dependencies | Everything above | Nothing, but it can abort a transaction with `40001`, so callers must retry |

## 1. Lost update under READ COMMITTED

An account holds 100.00. Two sessions each want to withdraw. Both read the balance, compute "what I read minus my amount", and write it back.

| Step | Session 1 (withdraws 30.00) | Session 2 (withdraws 50.00) |
|---|---|---|
| 1 | reads 100.00 | |
| 2 | | reads 100.00 |
| 3 | writes 70.00, commits | |
| 4 | | writes 50.00, commits |

The final balance is 50.00. 80.00 was withdrawn, only 50.00 left the account. Session 1's withdrawal is gone. READ COMMITTED allowed it: session 2's `UPDATE` waits for nothing, because session 1 already committed, and it overwrites the row with a number computed from a stale read.

Spec: "lost update under READ COMMITTED: two naive read-modify-write withdrawals, and one of them disappears".

This is why the ledger never computes a new balance in the application from an unlocked read.

Three ways to fix it, each shown by a spec:

1. **Do the arithmetic in the statement:** `UPDATE ... SET balance = balance - 30`. The second statement starts from the committed 70.00 and ends at 20.00. This is the atomic strategy ([ADR 0003](adr/0003-lock-strategy.md)). Spec: "lost update fix 1".
2. **Lock before reading:** `SELECT ... FOR UPDATE`. Session 2's select waits until session 1 commits, then reads 70.00. This is the pessimistic strategy. Spec: "lost update fix 2".
3. **Use REPEATABLE READ and let PostgreSQL abort the loser:** session 2 tries to update a row that changed after its snapshot and gets `40001`. Nothing is overwritten, and the caller retries. This is the same idea as the optimistic strategy, where the version column plays the part of the snapshot check. Spec: "lost update under REPEATABLE READ: PostgreSQL aborts the second writer with 40001 instead of overwriting".

## 2. Write skew under REPEATABLE READ

A rule that spans two rows: an owner's two accounts must hold at least 100.00 together. The database does not know the rule. Each transaction checks it in code before it withdraws 60.00 from one of the two accounts. The accounts hold 100.00 each.

| Step | Session 1 | Session 2 |
|---|---|---|
| 1 | reads both: 200.00. 200 - 60 >= 100, allowed | |
| 2 | | reads both: 200.00. 200 - 60 >= 100, allowed |
| 3 | withdraws 60.00 from account A, commits | |
| 4 | | withdraws 60.00 from account B, commits |

The total is now 80.00. Each transaction was right about its own snapshot, and together they broke the rule. They wrote different rows, so there is no write-write conflict for REPEATABLE READ to detect, and neither waits nor fails.

Spec: "write skew under REPEATABLE READ: two transactions each keep the rule, together they break it".

A single-row `CHECK (balance >= 0)` cannot catch this, because no single row went negative. The same happens in any rule over a set of rows.

The same interleaving under **SERIALIZABLE** ends differently: PostgreSQL notices that each transaction read something the other wrote, which is a cycle that no serial order could produce, and aborts the second committer with `40001`. Only one withdrawal happens and the rule holds. Spec: "write skew under SERIALIZABLE: the same interleaving is stopped, the second committer gets 40001".

Without SERIALIZABLE, the other fix is to make the two sessions meet: lock both rows first (`SELECT ... FOR UPDATE`, in id order), then check the rule. The second session waits, then sees 140.00 and refuses itself. Spec: "write skew fix without SERIALIZABLE: lock both rows first, then check the rule".

## 3. SERIALIZABLE with a `40001` retry and jitter

SERIALIZABLE is simple to reason about and puts the cost on the caller: any transaction may be aborted with SQLSTATE `40001`, and the correct response is to run it again from the start.

The experiment runs the rule from section 2 with eight concurrent callers. Each wants to withdraw 30.00 and the rule allows exactly three such withdrawals (200 - 3 x 30 = 110 >= 100, but a fourth would leave 80). All eight read before anyone writes, which forces conflicts. When a caller gets `40001` it rolls back, waits a random time and tries again.

The retry wait is the same `RetryPolicy` the transfer service uses: the ceiling doubles with every failure and the actual wait is random between zero and the ceiling ("full jitter"). Without the randomness, callers that failed together would retry together and collide again. Without the doubling, a crowd would keep hitting the same wall.

Result of the spec on a typical run: three withdrawals succeed, five callers see the rule refuse them (the total has fallen to 110 by then), nobody gives up, and the total ends at 110.00. The count of `40001` failures varies from run to run (the last local run counted 12); the end state does not. The spec asserts the end state and that at least one `40001` happened.

Spec: "SERIALIZABLE with retry and jitter: eight concurrent withdrawals end with exactly the number the rule allows".

## What the ledger itself uses

The ledger runs at READ COMMITTED, the PostgreSQL default, and does not rely on a higher level. Each strategy removes the lost update in its own way (lock first, version check, or arithmetic inside the UPDATE), and the only rule that spans rows, that the journal of a transfer sums to zero, is checked by a trigger at commit. PostgreSQL's `40001` and `40P01` aborts, if they happen, are turned into the same retryable `ConcurrentUpdate` the optimistic strategy uses (`JooqUnitOfWork`).

A rule like the one in section 2 (a limit across several accounts) would need SERIALIZABLE, or explicit locks on all the rows it reads. The ledger has no such rule today.
