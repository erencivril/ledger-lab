# ledger-lab

[![ci](https://github.com/erencivril/ledger-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/erencivril/ledger-lab/actions/workflows/ci.yml)

A small Java 21 service that moves money between accounts. Every transfer is recorded as a balanced double-entry journal, accounts can hold different currencies, and retries are safe.

It is a learning project, built the way I would build a small payments service: money invariants first, then the API around them, then evidence that the invariants hold under load and failure. The stack is Java 21, SparkJava, jOOQ, Flyway, PostgreSQL 16, Redis 7, HikariCP, Jackson, Spock and Testcontainers. It is the Java companion to [paywall-lab](https://github.com/erencivril/paywall-lab), which does a similar job in Go.

## Why

The interesting part of a transfer API is not the HTTP. It is a short list of facts that must always be true, even when requests are retried or run in parallel:

1. Money is exact. No `double`, no silent rounding.
2. Every transfer is a journal whose entries add up to zero in each currency.
3. A customer balance never goes below zero.
4. A transfer to the same account is rejected, and the amount must be greater than zero.
5. A retried request does not move money twice.
6. Two transfers that touch the same accounts at the same time do not deadlock or lose an update.

The code and the tests are organised around these six facts. `LedgerAudit` is the single definition of "the ledger is correct" (facts 2, 3 and the match between balances and journal); the stress tests and the benchmark both run it after the load stops.

More detail lives in:

- [docs/adr](docs/adr/README.md): the design decisions, with their evidence.
- [docs/failure-modes.md](docs/failure-modes.md): what happens when something breaks, and which spec proves it.
- [docs/isolation.md](docs/isolation.md): lost update, write skew and serialization retries, shown on PostgreSQL.
- [docs/benchmarks.md](docs/benchmarks.md): the three lock strategies compared, with the hardware.

## Architecture

```mermaid
flowchart LR
    C[client] --> R[http: SparkJava routes]
    R --> S[application: TransferService, AccountService]
    S --> L[TransferStrategy: pessimistic / optimistic / atomic]
    S --> D[domain: Money, TransferPlanner, Journal, LedgerAudit]
    S --> P[persistence: jOOQ repositories]
    S --> X[cache: Redis limiter + idempotency cache]
    P --> PG[(PostgreSQL)]
    X --> RD[(Redis)]
```

| Package | Role | Allowed to depend on |
|---|---|---|
| `domain` | Money, currencies, FX conversion, accounts, the journal, `TransferPlanner`. Plain Java. | JDK only |
| `application` | `TransferService` (with its retry loop), `AccountService`, the three `TransferStrategy` implementations, and the interfaces (`Ports`) they need from the outside. | domain |
| `persistence` | jOOQ repositories, the transaction wrapper, Flyway and the connection pool. | domain, application |
| `cache` | Redis velocity limit and idempotency cache. | domain, application |
| `http` | SparkJava routes, JSON, error mapping. | domain, application |

`App` is the one place that wires everything together. An ArchUnit spec fails the build if a layer reaches into one it should not know about, for example if `domain` imports a framework.

## Design decisions

### Money is `BigDecimal` at the currency's scale

`Money` is a record of a `BigDecimal` and a `Currency`. The amount is always stored at the currency's own scale: 2 decimals for USD, 0 for JPY, 3 for KWD. The constructor does not round. If a client sends `1.005` USD the request is rejected with `invalid_amount`, because silently choosing a cent is how money goes missing. Rounding happens in one place only, currency conversion, and it is `HALF_EVEN` (banker's rounding), so a long series of conversions does not drift in one direction. Amounts travel as JSON strings and are parsed straight into `BigDecimal`, never through a double.

### Double-entry journal

A transfer writes one `transfers` row and at least two `journal_entries` rows: negative for the debited account, positive for the credited one. `Journal` is a record whose constructor throws unless the entries sum to zero in every currency, so an unbalanced journal cannot exist in the domain. A deferred constraint trigger in PostgreSQL (`V3__journal_balance_trigger.sql`) checks the same thing at commit, so a bug in the application cannot persist one either. There is an integration test that tries.

Cross-currency transfers cannot balance with two entries, because 50 USD and 46 EUR are not the same amount. They are written as four entries through a treasury account per currency: the sender is debited in USD and the USD treasury is credited, then the EUR treasury is debited and the receiver is credited in EUR. Each currency nets to zero on its own. Treasury accounts have no stored balance (it would be a hot row that every transfer in that currency locks); their balance is the sum of their entries. They are never visible through the API.

The rate that was used is stored on the transfer row. The FX table is seeded by migration `V2` and is fixed, which keeps results repeatable. The rates are illustrative, not market data.

Customer accounts keep a `balance` column next to the journal, with `CHECK (balance >= 0)`. That is a deliberate duplicate of what the entries add up to. The integration tests check that the two always agree.

### Locking: three strategies, one default

`TransferService` runs one database transaction per transfer. Inside it a `TransferStrategy` changes the two balances safely; everything else (idempotency claim, transfer row, journal) is the same for all of them. Choose with `LOCK_STRATEGY`:

| `LOCK_STRATEGY` | How | Retries |
|---|---|---|
| `pessimistic` (default) | `SELECT ... FOR UPDATE` on both rows, then read, decide, write | never needed |
| `optimistic` | plain `SELECT`, then `UPDATE ... WHERE version = ?`; a lost race rolls back and the whole transfer is run again | yes, up to `TRANSFER_MAX_ATTEMPTS` with jitter |
| `atomic` | `UPDATE ... SET balance = balance - ? WHERE balance >= ?`: the database checks and changes in one statement | never needed |

All three touch the two rows in ascending id order, whichever one is the sender. If two transfers between A and B ran in opposite directions and each locked its own sender first, one would hold A and wait for B while the other held B and waited for A. With a fixed order both queue on the same first row, so the cycle cannot form. I checked this once by removing the `sorted()` call: 381 of 400 concurrent transfers failed with `deadlock detected`. A control test locks two rows in opposite orders on purpose and asserts that PostgreSQL reports `40P01`, so the concurrency tests would notice a bad ordering.

Pessimistic stays the default because its behaviour does not depend on the workload: a conflict means waiting, never failing. The optimistic strategy matches it on spread-out traffic and falls apart on a hot account. See [ADR 0003](docs/adr/0003-lock-strategy.md) and the measurements in [docs/benchmarks.md](docs/benchmarks.md):

| 3,000 transfers, 16 threads, median of 3 runs | pessimistic | optimistic | atomic |
|---|---:|---:|---:|
| 200 accounts, random pairs: committed per second | 208 | 227 | 229 |
| 80% of transfers on one account: committed per second | 161 | 86 | 173 |
| same, transfers that gave up after 5 attempts | 0 | 1,329 | 0 |

A 2-vCPU shared VPS running the load generator, the application and PostgreSQL together: it compares the strategies and says nothing about capacity. Differences of about 10% are inside the spread between runs.

When a transfer loses a race five times in a row, the client gets `409 concurrent_update` with `Retry-After`. Nothing moved, and repeating the request with the same key is safe. PostgreSQL's own deadlock (`40P01`) and serialization (`40001`) aborts are mapped to the same retryable error.

### Idempotency

`POST /transfers` requires an `Idempotency-Key` header (at most 255 characters).

- Same key, same body: `200 OK` with the original transfer and `Idempotency-Replayed: true`. The first call returns `201 Created`.
- Same key, different body: `409` with code `idempotency_key_reused`.
- "Same body" means the same source, destination, amount and currency. The amount is normalised first, so `10` and `10.00` count as the same request. The body is compared by a SHA-256 hash stored next to the key.

PostgreSQL is the source of truth. The key is claimed with `INSERT ... ON CONFLICT DO NOTHING` on the primary key of `idempotency_keys`, inside the same transaction that moves the money. If two requests with the same key arrive together, the second insert waits for the first transaction. When the first commits, the second sees a conflict and returns the stored result. When it rolls back, the second one proceeds. Because the claim and the transfer commit together, there is no state where the key exists but the money did not move.

A consequence worth knowing: a transfer that is rejected (insufficient funds, unknown account) rolls back its claim, so the same key can be used again after the client fixes the request. Some systems store failures too. This one does not.

### What Redis is and is not trusted for

Redis does two jobs and neither affects correctness.

1. **Velocity limit.** A sliding window per source account, kept in a sorted set and updated by one Lua script so the check and the update are atomic across app instances. The script uses the Redis clock. The default is 10 transfers per 60 seconds, set with `VELOCITY_MAX_TRANSFERS` and `VELOCITY_WINDOW_SECONDS`. The next one gets `429` with code `velocity_limit_exceeded` and a `Retry-After` header. The limit counts attempts that get past the idempotency lookup, so a replay of a finished transfer is free, but a transfer later rejected for insufficient funds still counts. If Redis is down the limiter lets the transfer through and logs a warning (`VELOCITY_FAIL_OPEN=false` makes it refuse the transfer instead). I chose fail-open because the ledger is correct without this rule; it is a risk control, not an integrity control.
2. **Idempotency cache.** After a transfer commits, `idem:<key>` is written with a 24 hour expiry so a replay can skip one query. A miss, a stale entry, garbage, or Redis being down all fall back to PostgreSQL. A cache entry is only used when its stored request hash matches the new request, and it only ever points at a transfer that is looked up in PostgreSQL. Nothing is ever created or moved because of what Redis says. Tests delete entries, poison them, and point the app at a Redis that is not there.

## Risk checks

Before money moves, `RiskEngine` looks at the payment and answers `ALLOW`, `WARN`, `STEP_UP`, `HOLD` or `BLOCK`, with the reason codes of the rules that fired. This part of the project is about payment correctness, not about fraud modelling: the rules are simple heuristics with thresholds I chose, and they will be wrong in both directions.

| Action | What happens |
|---|---|
| `ALLOW`, `WARN` | The transfer goes ahead. `WARN` is kept in the audit table for review. |
| `STEP_UP` | `403 step_up_required`. Nothing moved. Send the same request again with `"stepUpConfirmed": true` and it is treated as a warning. This is a stand-in for a second factor, which this project does not have. |
| `HOLD` | The sender is debited, the money sits in a hold account, the transfer is `PENDING` and the answer is `202`. `POST /transfers/{id}/release` pays the payee, `.../reject` refunds the sender. |
| `BLOCK` | `403 blocked_by_risk`. Nothing moved. |

The baseline rules of the first rule set (`src/main/resources/risk/ruleset-v1.yaml`):

| Rule | Fires when |
|---|---|
| `FIRST_PAYMENT_LARGE` | the sender has never completed a payment to this payee and the amount is at least 1,000 USD |
| `AMOUNT_DEVIATION` | the amount is at least 5 times the sender's average payment, with at least 3 earlier payments |
| `NEW_PAYEE_ACCOUNT` | the payee account is under 72 hours old and the amount is at least 300 USD |

Version 2 adds `NAME_NO_MATCH` (STEP_UP), `MOCK_SANCTIONS` (BLOCK), and `VELOCITY_1H` / `VELOCITY_24H` (HOLD). Windows count attempted payments and sum their USD amounts, including the current attempt, atomically in Redis. The boundaries are `(now - window, now]` using the Redis clock. The limits are 10 attempts or 10,000 USD in 1h, and 30 attempts or 25,000 USD in 24h. An unavailable velocity store always produces `HOLD` with `VELOCITY_UNAVAILABLE`, even if a rule is disabled. The older per-minute HTTP throttle is separate.

Optional `payeeName` is compared with the account owner's name after Unicode NFKD normalisation, accent removal, case folding and punctuation/space cleanup. Equal names are MATCH, a normalised Levenshtein similarity of at least 0.8 is CLOSE, otherwise NO_MATCH. This is a local VoP-style example, not a connection to a verification network. Missing names are NOT_PROVIDED and not a mismatch. It does not handle every script, transliteration or confusable character. The mock sanctions list contains only `Example Restricted Person` and `Synthetic Blocked Entity`, checks the actual owner rather than the supplied name, and is not a real screening service.

Rules live in YAML, are loaded into PostgreSQL as a numbered version, and a published version cannot change: the application compares the stored hash with the file and refuses to start if they differ. Every decision is written to `risk_decisions` with the version and the rules that fired, and those tables reject UPDATE and DELETE. Decisions are made before the transfer transaction, so a payment that lands in between is not seen. See [ADR 0008](docs/adr/0008-risk-engine-in-the-domain.md) and, for the hold, [ADR 0009](docs/adr/0009-holds-use-a-hold-account.md).

`LedgerAudit` stays true while transfers wait: the hold account has no stored balance, and the audit checks that its entries add up to the pending transfers. A crash between the hold and the decision loses nothing, see [docs/failure-modes.md](docs/failure-modes.md).

The risk history has a small CQRS read model: `risk_features_payments`, `risk_features_sender`, and `risk_features_payee`. Completed payments append to a transactional outbox in PostgreSQL. A polling worker uses `FOR UPDATE SKIP LOCKED` and commits projection and acknowledgement together. Redelivery is idempotent by transfer id. Unprojected payments are included directly from the ledger when risk history is read, so a stopped poller does not hide recent payments. See [ADR 0010](docs/adr/0010-poll-the-transactional-outbox.md).

### Synthetic back-test

```sh
./gradlew riskGenerate -Psamples=10000 -Pseed=42 -Poutput=build/synthetic.ndjson
./gradlew riskBacktest -Psamples=10000 -Pseed=42 -Pk=100
# Evaluate exactly the observations in a saved file instead:
./gradlew riskBacktest -Pinput=build/synthetic.ndjson -Pk=100
```

These are invented, independent observations: normal payments plus injected scam-like and mule-like bursts. They are not a real transaction population, a graph reconstruction, ML, or a measured fraud rate. Labels and signal distributions are chosen by the generator. There is overlap: normal payments can look suspicious and harmful ones can be quiet. The same 10,000 observations are used for both rule sets, without fitting thresholds. CI uploads the output as `risk-backtest-jdk21` and `risk-backtest-jdk25`.

Actual output with seed 42, 10,000 observations, k=100:

| Version | TP | FP | TN | FN | Precision | Recall | Precision@100 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1396 | 1930 | 6045 | 629 | 0.4197 | 0.6894 | 0.9400 |
| 2 | 1773 | 2064 | 5911 | 252 | 0.4621 | 0.8756 | 1.0000 |

A positive prediction means STEP_UP, HOLD or BLOCK. WARN is not an intervention. TP is a labelled harmful payment caught, FP a normal one interrupted, TN a normal one left alone, FN a harmful one missed. Precision is `TP / (TP + FP)`; recall is `TP / (TP + FN)`. Precision@k is the harmful share of the first k observations in a review queue ranked by action severity, then number of triggered rules, then sample id for ties. There is no calibrated risk probability. Undefined ratios print `NaN`, and k is clamped to the dataset size.

Version 2 catches more of the injected patterns, but also interrupts more normal payments. Its precision here is under 50%. The high precision@100 comes from the artificial distributions and ranking, not proof of quality on real payments. Changing the seed or the distributions changes these numbers.

## API

Amounts are strings. Errors are always `{"code": "...", "message": "..."}`.

| Method and path | What it does |
|---|---|
| `POST /accounts` | Body: `owner`, `currency`, optional `initialBalance`. Returns `201`. An opening balance is paid by the treasury through a normal journal. |
| `GET /accounts/{id}` | Account with its balance. |
| `GET /accounts/{id}/statement?limit=20&cursor=...` | Journal entries, newest first. `limit` is 1 to 100. Pass `nextCursor` back as `cursor` for the next page; it is `null` on the last page. Each entry has the amount and the balance after it. |
| `POST /transfers` | Header `Idempotency-Key`. Body: `sourceAccountId`, `destinationAccountId`, `amount`, `currency` (the source account's currency), optional `payeeName` and `stepUpConfirmed`. `201` when completed, `202` when the risk checks hold it. |
| `GET /transfers/{id}` | One transfer, including the amount on each side, the FX rate used, its status and the risk decision. |
| `POST /transfers/{id}/release` | Finishes a held transfer: the payee is paid. `409 transfer_not_pending` if it was already decided. |
| `POST /transfers/{id}/reject` | Cancels a held transfer: the sender is refunded. |
| `GET /health` | `200` while PostgreSQL is reachable. Reports Redis too: `up`, or `degraded` when Redis is down. `503` if PostgreSQL is down. |

Currencies: `USD`, `EUR`, `GBP`, `TRY`, `JPY`, `KWD`. Codes are case-sensitive.

| Status | When | Codes |
|---|---|---|
| 400 | Malformed or invalid input | `invalid_json`, `missing_field`, `invalid_field`, `invalid_id`, `invalid_amount`, `unsupported_currency`, `invalid_owner`, `missing_idempotency_key`, `invalid_idempotency_key`, `invalid_limit`, `invalid_cursor` |
| 403 | The risk checks refused the payment, or want a confirmation first (the body lists `reasonCodes`) | `blocked_by_risk`, `step_up_required` |
| 404 | Unknown account, transfer or route | `account_not_found`, `transfer_not_found`, `route_not_found` |
| 409 | Idempotency key reused with a different body, a transfer that lost its race on every attempt (safe to repeat), or a hold that was already decided | `idempotency_key_reused`, `concurrent_update`, `transfer_not_pending` |
| 422 | The request is well formed but a rule refuses it | `same_account`, `insufficient_funds`, `currency_mismatch`, `no_fx_rate` |
| 429 | Velocity limit | `velocity_limit_exceeded` |
| 500 | Anything unexpected | `internal_error` |
| 503 | PostgreSQL cannot be reached (with `Retry-After`) | `storage_unavailable` |

Amounts that are zero, negative or have too many decimals are 400 (`invalid_amount`). An amount above 1,000,000,000,000 is also `invalid_amount`.

```sh
curl -X POST localhost:8080/accounts -H 'Content-Type: application/json' \
  -d '{"owner":"alice","currency":"USD","initialBalance":"100.00"}'
curl -X POST localhost:8080/accounts -H 'Content-Type: application/json' \
  -d '{"owner":"bob","currency":"EUR"}'

curl -i -X POST localhost:8080/transfers -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"sourceAccountId":"<alice>","destinationAccountId":"<bob>","amount":"25.00","currency":"USD"}'
# 201, destinationAmount 23.00 EUR, fxRate 0.92000000. Send it again: 200 and Idempotency-Replayed: true.
```

## Run it

You need Docker for the compose stack. For the Gradle commands you need a JDK 21; the Gradle wrapper is in the repo.

```sh
make up        # builds the image, starts app + postgres + redis, waits for healthchecks
make smoke     # two accounts, a transfer, a retry, a conflicting retry, the statement
make down      # stop and delete the database volume
```

Or without Docker for the app itself, with PostgreSQL and Redis running locally:

```sh
./gradlew run
```

Settings are environment variables:

| Variable | Default |
|---|---|
| `PORT` | `8080` |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/ledger`, `ledger`, `ledger` |
| `DATABASE_POOL_SIZE` | `10` |
| `DATABASE_CONNECT_TIMEOUT_MS` | `10000` (how long a request waits for a connection before it gets a 503) |
| `LOCK_STRATEGY` | `pessimistic` (or `optimistic`, `atomic`) |
| `TRANSFER_MAX_ATTEMPTS`, `TRANSFER_RETRY_BASE_MILLIS` | `5`, `5` |
| `REDIS_URL` | `redis://localhost:6379` |
| `VELOCITY_MAX_TRANSFERS` | `10` |
| `VELOCITY_WINDOW_SECONDS` | `60` |
| `VELOCITY_FAIL_OPEN` | `true` |
| `RISK_ENABLED` | `true` |
| `RISK_RULESET_VERSION` | newest bundled version |

The schema is created by Flyway when the app starts. The jOOQ classes are generated at build time from the same migration files using jOOQ's `DDLDatabase`, so building needs no running database and the generated code cannot drift from the schema.

## How it is tested

```sh
make test         # unit specs, no Docker needed
make integration  # starts PostgreSQL 16 and Redis 7 with Testcontainers
make mutation     # PIT on the domain package
make benchmark    # the three lock strategies compared (needs Docker, about 7 minutes)
make check        # unit + integration
```

**Unit specs (Spock 2 on Groovy 4).** 274 test results. The domain is tested with data tables: rounding at 0, 2 and 3 decimals including the exact half-way cases, FX conversion, every validation rule, balanced and unbalanced journals, and every kind of damage `LedgerAudit` must notice. Each strategy and the retry loop are tested with mocked ports, including lock order and the waits between attempts. An ArchUnit spec guards the layering.

**Integration specs.** 105 test results against real PostgreSQL and Redis. They cover the repositories through the services, the whole HTTP API, idempotent replays, 20 parallel requests with one key (exactly one transfer), rollback of rejected transfers, the database-level guards, the Redis limiter and Redis being wrong, empty or unreachable. Every spec that writes money ends by running the ledger audit over the whole database.

- **Stress, per strategy** (`StrategyStressIntegrationSpec`): randomized transfers across many accounts with a hot account on 32 threads; 400 transfers in both directions between two accounts; 300 requests of 1.00 against 100.00 (exactly 100 win). Each ends with the audit.
- **Failure modes** (`FailureModesIntegrationSpec`, [docs/failure-modes.md](docs/failure-modes.md)): a crash mid-transfer, a lost response after commit, a deadlock, and PostgreSQL going down and coming back.
- **Isolation** (`IsolationIntegrationSpec`, [docs/isolation.md](docs/isolation.md)): lost update at READ COMMITTED and its three fixes; write skew at REPEATABLE READ, stopped at SERIALIZABLE; a `40001` retry loop with jitter. Anomalies that need a particular order are produced with two hand-driven connections, not with sleeps.
- **The checks can fail** (`InvariantSuiteHasTeethIntegrationSpec`): a deliberately wrong strategy loses an update, and the audit reports it.

The original concurrency test still runs: 32 threads, 400 transfers in both directions between two accounts, 400 succeeded, no deadlocks, money conserved. Numbers are from a 2-vCPU VPS with PostgreSQL in Docker; read them as "it works under contention", not as a benchmark. For comparisons see [docs/benchmarks.md](docs/benchmarks.md), reproduced with `make benchmark`.

**Mutation testing (PIT).** PIT changes the domain code (flips a condition, removes a call, changes a return value) and checks that a test fails. It runs on `dev.erencivril.ledger.domain` with the `STRONGER` mutator set and the JUnit 5 plugin so Spock specs are run. Last run: 122 mutants, 122 killed, including `LedgerAudit` and the planner variant used by the atomic strategy. The build fails below a 90% threshold, set in `build.gradle`. CI uploads the HTML report as an artifact and writes the score and any surviving mutants to the run summary.

On its first runs PIT reported two survivors. The treasury map's defensive copy was never tested, and the currency check in `TransferPlanner` was hidden behind a similar check inside `Money`, so a loose assertion on the error message let the mutant live. Both became tests.

PIT only looks at the domain. The application and persistence layers are checked by the other tests, not by mutation testing.

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push to `main` and on pull requests:

1. `unit`: `./gradlew assemble test`, on JDK 21 and JDK 25.
2. `integration`: `./gradlew integrationTest` with Testcontainers, on JDK 21 and JDK 25; prints the stress and isolation numbers in the run summary.
3. `mutation`: `./gradlew pitest`, on JDK 21 and JDK 25; uploads the PIT report and writes the score to the summary.
4. `docker`: `docker compose up --build --wait`, then `scripts/smoke.sh`; then the built image is scanned with Trivy (HIGH and CRITICAL findings that have a fix fail the CI run; unfixed ones are listed but do not). Two Jetty 9.4 findings that only have commercial fixes are accepted in `.trivyignore` with an expiry date; the reasoning is in ADR 0006.
5. `secrets`: gitleaks over the working tree and the whole history.

The JDK 25 legs compile and run the project on 25; Gradle itself runs on 21.

Gradle's build cache is off, so tests are never skipped as "from cache".

## Known limitations

- **No authentication or ownership.** Anyone who knows an account id can transfer from it. This is the biggest gap if it were to be used for real.
- **FX is a fixed, illustrative table.** No live rates, no spread, no fees, no rate expiry. A rate and its inverse are not exact reciprocals. Only six currencies, as an enum.
- **No reversals.** A completed transfer is final. Only a held transfer can be cancelled (rejected), and only before it is released.
- **Idempotency keys are global and never expire.** They are not scoped to a client, and the table grows forever. A real service would scope keys per API client and delete old ones.
- **Velocity limit is simple.** Per source account only, counts attempts, and fails open by default.
- **SparkJava is unmaintained** (last release 2022). It is kept behind a thin routing layer with a named exit path, see [ADR 0006](docs/adr/0006-sparkjava-behind-a-thin-routing-layer.md); the image is scanned in CI.
- **No outbox yet.** Anything that must follow a transfer (notifications, feeds) would use a transactional outbox, see [ADR 0007](docs/adr/0007-outbox-instead-of-saga.md). It is a decision, not code.
- **Single PostgreSQL.** Row locks serialise transfers that share an account, which is the point, but a very busy account is a hot row. Scaling beyond one database would need partitioning by account and a different approach to cross-shard transfers (for example a saga or a transfer state machine), which this project does not have.
- **The treasury is the counterparty for FX and opening balances** and is not reconciled against anything external.
- **Operational basics are missing:** no metrics, no tracing, no request ids, no OpenAPI file, no graceful drain on shutdown beyond stopping Jetty.

## License

MIT
