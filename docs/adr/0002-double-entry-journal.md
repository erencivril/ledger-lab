# 0002. Every transfer is a balanced double-entry journal

Status: accepted

## Context

A balance column alone cannot say where money came from. If it is wrong there is nothing to compare it with. A journal of signed entries can be replayed, and the sum of all entries has a simple meaning: money is neither created nor destroyed.

## Decision

- A transfer writes one `transfers` row and at least two `journal_entries` rows. Debits are negative, credits are positive.
- `Journal` is a record whose constructor throws unless the entries sum to zero in every currency. An unbalanced journal cannot exist in the domain.
- A deferred constraint trigger in PostgreSQL (`V3__journal_balance_trigger.sql`) checks the same rule at commit, so an application bug cannot persist an unbalanced journal either.
- A cross-currency transfer is four entries, through one treasury account per currency, so that each currency nets to zero on its own.
- Customer accounts also keep a `balance` column with `CHECK (balance >= 0)`. It duplicates what the entries add up to, on purpose: it makes "can this account pay?" a single-row read.
- Treasury accounts have no stored balance. It would be a row every transfer in that currency has to lock. Their balance is the sum of their entries.
- `LedgerAudit` is the one definition of "the ledger is correct": every transfer sums to zero per currency, no customer balance is negative at any point of the replay, `balance_after` on every entry matches the replay, and the stored balance matches the replayed journal.

## Consequences

- Two numbers per account (the column and the sum of the entries) must always agree. That is checked after every stress test and every benchmark run, so a strategy that loses an update is caught.
- Reversals and holds would be new journals, never edits of old ones. There are none yet.

## Evidence

- [`JournalSpec`](../../src/test/groovy/dev/erencivril/ledger/domain/JournalSpec.groovy) and [`LedgerAuditSpec`](../../src/test/groovy/dev/erencivril/ledger/domain/LedgerAuditSpec.groovy): what balances, what does not, and every kind of damage the audit must notice.
- [`TransferServiceIntegrationSpec`](../../src/integrationTest/groovy/dev/erencivril/ledger/TransferServiceIntegrationSpec.groovy), "the database refuses an unbalanced journal even if the application writes one" and "the database refuses a negative customer balance".
- [`InvariantSuiteHasTeethIntegrationSpec`](../../src/integrationTest/groovy/dev/erencivril/ledger/InvariantSuiteHasTeethIntegrationSpec.groovy): a deliberately wrong strategy is caught by the audit, so the audit can fail.
- [`StrategyStressIntegrationSpec`](../../src/integrationTest/groovy/dev/erencivril/ledger/StrategyStressIntegrationSpec.groovy): randomized load with a hot account, audited afterwards, for each strategy.
