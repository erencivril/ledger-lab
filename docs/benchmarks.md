# Benchmarks

What this measures: the three locking strategies from [ADR 0003](adr/0003-lock-strategy.md), doing the same transfers on the same machine, with the ledger audited after every run.

What it does not measure: capacity. The load generator, the application and PostgreSQL all share two CPU cores, so the numbers say how the strategies compare here, not how many transfers a real deployment handles. Treat differences of a few percent as noise. The ranges in the tables show how much a single cell moves between runs.

## How to reproduce

```sh
./gradlew benchmark                                   # needs Docker; about 7 minutes on the machine below
./gradlew benchmark -PbenchArgs="-Dbench.runs=5"      # other options: bench.transfers, bench.threads, bench.seed
```

The report is printed and also written to `build/benchmark/report.md`. The runner is `src/benchmark/java/.../BenchmarkMain.java`.

How a run works:

- A fresh PostgreSQL 16 container with default settings. A pool of 16 connections and 16 worker threads.
- 200 USD accounts per run, each opened with 1,000.00 (the hot account with 10,000,000.00, so it cannot run out of money and the funds check does not change what is measured).
- 3,000 transfers of 0.01 to 5.00, from a list generated from a fixed seed (`20261110`) before the run, so every strategy gets exactly the same list.
- Two workloads. **normal**: source and destination picked uniformly from the 200 accounts. **hot**: 80% of the transfers have account 0 as source or destination (half each way), the rest are random.
- The service is configured as in production: 5 attempts, 5 ms base, full jitter. Nothing is tuned per strategy.
- One unmeasured warm-up run per strategy and scenario, then 3 measured runs. The table shows the median run by throughput, with the slowest and fastest in brackets.
- Throughput counts committed transfers per second of wall-clock time. Latency is per request, from the start of `transfer()` to its return, including retries and waits. A request that gave up after its retries counts in the latency but not as committed.
- After every run the whole ledger is audited with `LedgerAudit`. A violation stops the benchmark.

Columns: **Retries** is how many times an attempt was repeated after a lost race, **Aborted after retries** is how many transfers gave up with `concurrent_update` after 5 attempts (in a real deployment the client would get a 409 and could repeat), **Insufficient funds** is how many were refused by the funds rule (none are expected here).

## Results

Run on 2026-11-09 (UTC) on the code of the commit that added this file (the 3,000-transfer, 3-run configuration above).

### Hardware

- CPU: Intel Xeon Processor (Skylake, IBRS, no TSX), 2 logical CPUs
- Memory: 3.7 GiB
- OS: Linux 6.8.0-149-generic (amd64)
- JVM: OpenJDK 64-Bit Server VM 21.0.13+9-LTS
- Database: PostgreSQL 16 (postgres:16-alpine, default settings) in Docker on the same machine
- Pool: 16 connections, 16 worker threads
- Load: 3000 transfers per run, 3 runs per cell, seed 20261110
- Retry policy: 5 attempts, 5 ms base, full jitter (the application defaults)

#### normal: 200 accounts, pairs picked at random

| Strategy | Committed per second (median, min to max) | Committed of 3000 | p50 ms | p99 ms | Retries | Aborted after retries | Insufficient funds |
|---|---:|---:|---:|---:|---:|---:|---:|
| pessimistic | 208 (174 to 226) | 3000 | 67.6 | 195.0 | 0 | 0 | 0 |
| optimistic | 227 (203 to 300) | 3000 | 61.6 | 195.5 | 637 | 0 | 0 |
| atomic | 229 (218 to 237) | 3000 | 59.7 | 184.3 | 0 | 0 | 0 |

#### hot: 80% of transfers touch one account

| Strategy | Committed per second (median, min to max) | Committed of 3000 | p50 ms | p99 ms | Retries | Aborted after retries | Insufficient funds |
|---|---:|---:|---:|---:|---:|---:|---:|
| pessimistic | 161 (157 to 163) | 3000 | 33.0 | 678.9 | 0 | 0 | 0 |
| optimistic | 86 (74 to 88) | 1671 | 115.6 | 256.9 | 7110 | 1329 | 0 |
| atomic | 173 (170 to 215) | 3000 | 49.4 | 486.6 | 0 | 0 | 0 |

Every run was followed by a full ledger audit (balanced transfers, no negative balance, stored balance equals replayed journal): no violations.


## Reading the numbers

- **Normal workload.** All three commit all 3,000 transfers and are within about 10% of each other, which is inside the spread between runs of one cell (pessimistic ranged from 174 to 226). The optimistic strategy retried about one transfer in five (637 retries for 3,000 transfers) even with 200 accounts, because 16 threads are running and the random pairs sometimes meet. Nothing aborted. No strategy is clearly better here.
- **Hot account.** This is where the strategies separate. Pessimistic and atomic commit all 3,000 transfers. Optimistic commits about 1,670: 1,329 transfers lost five races in a row and gave up, and the 7,110 repeated attempts did the same work again. Its throughput is roughly half of the others. Its median latency is higher, its p99 is lower, because the transfers that would have waited longest simply gave up.
- **Pessimistic versus atomic.** On the hot account atomic was a little faster (173 against 161 transfers per second) with a lower p99 (487 ms against 679 ms), which fits its shorter critical section: it holds the row from the UPDATE to the commit and not from the first SELECT. The gap is about 7% and the ranges touch (157 to 163 against 170 to 215). A run of this size cannot rank them with confidence.
- **Why pessimistic remains the default.** It never retries and never aborts, and its behaviour on the hot account is the same kind of behaviour as on any other account: it waits. The optimistic strategy is fine until the workload develops a hot spot, and then it fails in a way that depends on traffic. The atomic strategy needs the rule to fit in one UPDATE.

## Variance, and an earlier run

The same command, run a first time before one column was added to the report, gave:

| Scenario | pessimistic | optimistic | atomic |
|---|---:|---:|---:|
| normal | 195 per second | 217 per second, 1 aborted | 236 per second |
| hot | 149 per second | 54 per second, 1,393 aborted | 193 per second |

The ordering on the hot account was the same, and the optimistic numbers on the hot account were worse. On the normal workload the three trade places within the noise. Run it yourself before relying on any single figure. The machine is a shared virtual server, and the other processes on it were not controlled.
