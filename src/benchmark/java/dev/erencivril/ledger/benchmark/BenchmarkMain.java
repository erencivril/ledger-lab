package dev.erencivril.ledger.benchmark;

import com.zaxxer.hikari.HikariDataSource;
import dev.erencivril.ledger.application.AccountService;
import dev.erencivril.ledger.application.ApplicationException;
import dev.erencivril.ledger.application.LockStrategyKind;
import dev.erencivril.ledger.application.Ports.IdempotencyCache;
import dev.erencivril.ledger.application.Ports.Pause;
import dev.erencivril.ledger.application.Ports.UnitOfWork;
import dev.erencivril.ledger.application.Ports.VelocityLimiter;
import dev.erencivril.ledger.application.RetryPolicy;
import dev.erencivril.ledger.application.TransferService;
import dev.erencivril.ledger.application.TransferService.TransferCommand;
import dev.erencivril.ledger.benchmark.Workload.Operation;
import dev.erencivril.ledger.benchmark.Workload.Scenario;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.DomainException;
import dev.erencivril.ledger.domain.FxRateTable;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.TransferPlanner;
import dev.erencivril.ledger.domain.TreasuryAccounts;
import dev.erencivril.ledger.persistence.Database;
import dev.erencivril.ledger.persistence.JooqUnitOfWork;
import dev.erencivril.ledger.persistence.LedgerAuditReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Load runner: the three strategies against two scenarios, with throughput, latency percentiles, retries
 * and aborts. Start it with {@code ./gradlew benchmark}. It starts its own PostgreSQL 16 in Docker with
 * default settings, so the only requirement is Docker.
 *
 * <p>Rules that keep the numbers honest:
 * <ul>
 *   <li>The workload is generated from a fixed seed and is identical for every strategy.
 *   <li>The service is configured exactly like production: the default retry policy, no special tuning.
 *   <li>After every run the whole ledger is audited. A run that leaves the ledger inconsistent stops the benchmark.
 *   <li>Each combination runs several times; the table shows the median run, the range is printed too.
 *   <li>A warm-up run, not measured, comes first.
 * </ul>
 *
 * <p>Options (system properties): bench.transfers (default 3000), bench.threads (16), bench.runs (3),
 * bench.seed (20261110).
 */
public final class BenchmarkMain {

    private static final int INITIAL_BALANCE = 1_000; // per account, in USD
    private static final int HOT_ACCOUNT_BALANCE = 10_000_000;
    private static final int WARMUP_TRANSFERS = 500;

    private final int transfers = Integer.getInteger("bench.transfers", 3000);
    private final int threads = Integer.getInteger("bench.threads", 16);
    private final int runs = Integer.getInteger("bench.runs", 3);
    private final long seed = Long.getLong("bench.seed", 20261110L);

    public static void main(String[] args) throws Exception {
        new BenchmarkMain().run();
    }

    private void run() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("ledger")
                .withUsername("ledger")
                .withPassword("ledger")) {
            postgres.start();
            try (HikariDataSource dataSource =
                    Database.connect(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), threads)) {
                Database.migrate(dataSource);
                UnitOfWork unitOfWork = new JooqUnitOfWork(dataSource);
                TransferPlanner planner = unitOfWork.execute(tx -> new TransferPlanner(
                        new FxRateTable(tx.fxRates().findAll()), new TreasuryAccounts(tx.accounts().treasuryIds())));
                report(unitOfWork, planner, dataSource);
            }
        }
    }

    private void report(UnitOfWork unitOfWork, TransferPlanner planner, HikariDataSource dataSource) {
        RetryPolicy production = new RetryPolicy(5, 5); // the defaults of TRANSFER_MAX_ATTEMPTS and TRANSFER_RETRY_BASE_MILLIS

        StringBuilder out = new StringBuilder();
        out.append("## Hardware\n\n").append(Hardware.describe());
        out.append("- Database: PostgreSQL 16 (postgres:16-alpine, default settings) in Docker on the same machine\n");
        out.append("- Pool: ").append(threads).append(" connections, ").append(threads).append(" worker threads\n");
        out.append("- Load: ").append(transfers).append(" transfers per run, ").append(runs).append(" runs per cell, seed ").append(seed).append("\n");
        out.append("- Retry policy: 5 attempts, 5 ms base, full jitter (the application defaults)\n\n");

        for (Scenario scenario : Scenario.values()) {
            out.append("### ").append(scenario.description).append("\n\n");
            out.append("| Strategy | Committed per second (median, min to max) | Committed of " + transfers + " | p50 ms | p99 ms | Retries | Aborted after retries | Insufficient funds |\n");
            out.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
            List<Operation> operations = Workload.generate(scenario, transfers, seed);
            for (LockStrategyKind kind : LockStrategyKind.values()) {
                List<RunResult> results = new ArrayList<>();
                // The warm-up uses its own accounts and its own short workload.
                measure(unitOfWork, planner, dataSource, kind, production, Workload.generate(scenario, WARMUP_TRANSFERS, seed + 1));
                for (int i = 0; i < runs; i++) {
                    results.add(measure(unitOfWork, planner, dataSource, kind, production, operations));
                }
                out.append(row(kind, results));
                System.err.println("done " + scenario + " " + kind);
            }
            out.append("\n");
        }
        out.append("Every run was followed by a full ledger audit (balanced transfers, no negative balance, stored balance equals replayed journal): no violations.\n");
        System.out.println(out);
        writeReport(out.toString());
    }

    /** The same report goes to build/benchmark/report.md, because the console is mixed with container logs. */
    private static void writeReport(String report) {
        try {
            Path file = Path.of("build", "benchmark", "report.md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String row(LockStrategyKind kind, List<RunResult> results) {
        List<RunResult> byThroughput = new ArrayList<>(results);
        byThroughput.sort(Comparator.comparingDouble(RunResult::committedPerSecond));
        RunResult median = byThroughput.get(byThroughput.size() / 2);
        RunResult slowest = byThroughput.get(0);
        RunResult fastest = byThroughput.get(byThroughput.size() - 1);
        return String.format(
                "| %s | %.0f (%.0f to %.0f) | %d | %.1f | %.1f | %d | %d | %d |%n",
                kind.name().toLowerCase(),
                median.committedPerSecond(),
                slowest.committedPerSecond(),
                fastest.committedPerSecond(),
                median.committed(),
                median.p50Millis(),
                median.p99Millis(),
                median.retries(),
                median.abortedAfterRetries(),
                median.rejectedInsufficientFunds());
    }

    /** Opens fresh accounts, runs the operations on the worker threads, audits the ledger, returns the numbers. */
    private RunResult measure(
            UnitOfWork unitOfWork,
            TransferPlanner planner,
            HikariDataSource dataSource,
            LockStrategyKind kind,
            RetryPolicy retryPolicy,
            List<Operation> operations) {
        List<Account> accounts = openAccounts(unitOfWork, planner);
        TransferService service = new TransferService(
                unitOfWork,
                kind.create(planner),
                VelocityLimiter.unlimited(),
                IdempotencyCache.none(),
                Clock.systemUTC(),
                retryPolicy,
                Pause.sleeping());

        long[] latenciesNanos = new long[operations.size()];
        AtomicLong committed = new AtomicLong();
        AtomicLong insufficient = new AtomicLong();
        AtomicLong aborted = new AtomicLong();
        String runId = kind + "-" + System.nanoTime();
        long begin = System.nanoTime();
        runWorkers(operations.size(), threads, index -> {
            Operation op = operations.get(index);
            TransferCommand command = new TransferCommand(
                    runId + "-" + index, accounts.get(op.fromIndex()).id(),
                    accounts.get(op.toIndex()).id(), new Money(op.amount(), Currency.USD));
            long started = System.nanoTime();
            try {
                service.transfer(command);
                committed.incrementAndGet();
            } catch (DomainException.InsufficientFunds e) {
                insufficient.incrementAndGet();
            } catch (ApplicationException.ConcurrentUpdate e) {
                aborted.incrementAndGet();
            }
            latenciesNanos[index] = System.nanoTime() - started;
        });
        double seconds = (System.nanoTime() - begin) / 1e9;
        if (committed.get() + insufficient.get() + aborted.get() != operations.size()) {
            throw new IllegalStateException("Benchmark outcome count does not match requested operations");
        }

        List<String> violations = LedgerAuditReader.violations(dataSource);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Ledger audit failed after " + kind + ": " + violations.subList(0, Math.min(5, violations.size())));
        }

        Arrays.sort(latenciesNanos);
        return new RunResult(
                committed.get(),
                insufficient.get(),
                aborted.get(),
                service.retries(),
                seconds,
                percentile(latenciesNanos, 0.50) / 1e6,
                percentile(latenciesNanos, 0.99) / 1e6);
    }

    static void runWorkers(int operations, int threads, java.util.function.IntConsumer operation) {
        AtomicInteger next = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        AtomicInteger processed = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    gate.await();
                    int index;
                    while (failure.get() == null && (index = next.getAndIncrement()) < operations) {
                        operation.accept(index);
                        processed.incrementAndGet();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            workers.add(worker);
            worker.start();
        }
        gate.countDown();
        try {
            for (Thread worker : workers) worker.join();
        } catch (InterruptedException e) {
            workers.forEach(Thread::interrupt);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark interrupted", e);
        }
        if (failure.get() != null) throw new IllegalStateException("Benchmark worker failed", failure.get());
        if (processed.get() != operations) throw new IllegalStateException("Incomplete benchmark workload");
    }

    private static double percentile(long[] sorted, double fraction) {
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, index)];
    }

    private static List<Account> openAccounts(UnitOfWork unitOfWork, TransferPlanner planner) {
        AccountService accountService = new AccountService(unitOfWork, planner, Clock.systemUTC());
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < Workload.ACCOUNT_COUNT; i++) {
            int balance = i == 0 ? HOT_ACCOUNT_BALANCE : INITIAL_BALANCE;
            accounts.add(accountService.open("bench", Currency.USD, new Money(new BigDecimal(balance).setScale(2), Currency.USD)));
        }
        return accounts;
    }

}
