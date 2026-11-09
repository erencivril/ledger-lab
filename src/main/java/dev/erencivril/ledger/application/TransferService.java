package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.ApplicationException.ConcurrentUpdate;
import dev.erencivril.ledger.application.ApplicationException.IdempotencyKeyReused;
import dev.erencivril.ledger.application.Ports.CachedKey;
import dev.erencivril.ledger.application.Ports.IdempotencyCache;
import dev.erencivril.ledger.application.Ports.IdempotencyRecord;
import dev.erencivril.ledger.application.Ports.LedgerTransaction;
import dev.erencivril.ledger.application.Ports.NewJournalEntry;
import dev.erencivril.ledger.application.Ports.Pause;
import dev.erencivril.ledger.application.Ports.UnitOfWork;
import dev.erencivril.ledger.application.Ports.VelocityLimiter;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.Transfer;
import dev.erencivril.ledger.domain.TransferKind;
import dev.erencivril.ledger.domain.TransferPlan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves money between two accounts, once per Idempotency-Key.
 *
 * <p>Order of work for a request:
 * <ol>
 *   <li>Look for an earlier result for the key (Redis first, then Postgres). A replay returns early and
 *       does not use up velocity budget.
 *   <li>Check the velocity limit for the source account.
 *   <li>In one database transaction: claim the key, change both balances (the {@link TransferStrategy}
 *       decides how), write the transfer and the journal.
 * </ol>
 *
 * If the strategy reports a {@link ConcurrentUpdate}, the whole transaction, including the key claim, is
 * rolled back and run again, up to the limit in the {@link RetryPolicy}. Only that exception is retried.
 * A business rejection such as insufficient funds is final and is never retried.
 */
public final class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final UnitOfWork unitOfWork;
    private final TransferStrategy strategy;
    private final VelocityLimiter velocityLimiter;
    private final IdempotencyCache cache;
    private final Clock clock;
    private final RetryPolicy retryPolicy;
    private final Pause pause;
    private final AtomicLong retries = new AtomicLong();

    public TransferService(
            UnitOfWork unitOfWork,
            TransferStrategy strategy,
            VelocityLimiter velocityLimiter,
            IdempotencyCache cache,
            Clock clock,
            RetryPolicy retryPolicy,
            Pause pause) {
        this.unitOfWork = unitOfWork;
        this.strategy = strategy;
        this.velocityLimiter = velocityLimiter;
        this.cache = cache;
        this.clock = clock;
        this.retryPolicy = retryPolicy;
        this.pause = pause;
    }

    /** How many times an attempt was repeated because of a {@link ConcurrentUpdate}, since this service was created. */
    public long retries() {
        return retries.get();
    }

    public record TransferCommand(
            String idempotencyKey, UUID sourceAccountId, UUID destinationAccountId, Money amount) {

        /**
         * A hash of the request body. Two requests are "the same" when this matches, so the amount is
         * normalised first: 10 and 10.00 are the same request.
         */
        String fingerprint() {
            String canonical = sourceAccountId + "|" + destinationAccountId + "|" + amount.amount().toPlainString()
                    + "|" + amount.currency();
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** @param replayed true when this is the stored result of an earlier request with the same key */
    public record TransferResult(Transfer transfer, boolean replayed) {}

    public TransferResult transfer(TransferCommand command) {
        String fingerprint = command.fingerprint();

        Optional<TransferResult> replay = findReplay(command.idempotencyKey(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        velocityLimiter.acquire(command.sourceAccountId());

        TransferResult result = executeWithRetries(command, fingerprint);
        cache.put(command.idempotencyKey(), fingerprint, result.transfer().id());
        return result;
    }

    /**
     * Runs the transfer transaction. When it loses a race it waits a random time and runs it again from
     * the start; the failed attempt rolled back, so the next one sees a clean slate.
     */
    private TransferResult executeWithRetries(TransferCommand command, String fingerprint) {
        int attempt = 1;
        while (true) {
            try {
                return unitOfWork.execute(tx -> executeInTransaction(tx, command, fingerprint));
            } catch (ConcurrentUpdate e) {
                if (attempt >= retryPolicy.maxAttempts()) {
                    throw e;
                }
                retries.incrementAndGet();
                pause.pause(retryPolicy.jitterMillis(attempt, ThreadLocalRandom.current()));
                attempt++;
            }
        }
    }

    public Transfer get(UUID id) {
        return unitOfWork
                .execute(tx -> tx.transfers().find(id))
                .orElseThrow(() -> new ApplicationException.TransferNotFound(id));
    }

    private Optional<TransferResult> findReplay(String key, String fingerprint) {
        Optional<CachedKey> cached = cache.get(key);
        if (cached.isPresent()) {
            requireSameRequest(key, fingerprint, cached.get().fingerprint());
            Optional<Transfer> transfer =
                    unitOfWork.execute(tx -> tx.transfers().find(cached.get().transferId()));
            if (transfer.isPresent()) {
                return Optional.of(new TransferResult(transfer.get(), true));
            }
            log.warn("Idempotency cache points at a missing transfer for key {}; falling back to Postgres", key);
        }
        return unitOfWork.execute(tx -> tx.idempotency().find(key).map(record -> replayOf(tx, record, fingerprint)));
    }

    private TransferResult executeInTransaction(LedgerTransaction tx, TransferCommand command, String fingerprint) {
        if (!tx.idempotency().tryClaim(command.idempotencyKey(), fingerprint)) {
            // Another request committed this key between our lookup and now.
            IdempotencyRecord record = tx.idempotency()
                    .find(command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("Idempotency key vanished after conflict"));
            return replayOf(tx, record, fingerprint);
        }

        BalanceChange change = strategy.changeBalances(
                tx, command.sourceAccountId(), command.destinationAccountId(), command.amount());
        TransferPlan plan = change.plan();

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                TransferKind.TRANSFER,
                command.sourceAccountId(),
                command.destinationAccountId(),
                plan.sourceAmount(),
                plan.destinationAmount(),
                plan.fxRate(),
                now);
        tx.transfers().insert(transfer);

        List<NewJournalEntry> entries = plan.journal().lines().stream()
                .map(line -> new NewJournalEntry(line.accountId(), line.amount(), balanceAfter(line.accountId(), command, change)))
                .toList();
        tx.journal().append(transfer.id(), entries, now);
        tx.idempotency().attach(command.idempotencyKey(), transfer.id());
        return new TransferResult(transfer, false);
    }

    private static Money balanceAfter(UUID accountId, TransferCommand command, BalanceChange change) {
        if (accountId.equals(command.sourceAccountId())) {
            return change.sourceBalanceAfter();
        }
        if (accountId.equals(command.destinationAccountId())) {
            return change.destinationBalanceAfter();
        }
        return null; // treasury accounts keep no stored balance
    }

    private TransferResult replayOf(LedgerTransaction tx, IdempotencyRecord record, String fingerprint) {
        requireSameRequest(record.key(), fingerprint, record.fingerprint());
        if (record.transferId() == null) {
            throw new IllegalStateException("Idempotency key " + record.key() + " has no transfer");
        }
        Transfer transfer = tx.transfers()
                .find(record.transferId())
                .orElseThrow(() -> new IllegalStateException("Transfer missing for key " + record.key()));
        return new TransferResult(transfer, true);
    }

    private static void requireSameRequest(String key, String expected, String stored) {
        if (!expected.equals(stored)) {
            throw new IdempotencyKeyReused(key);
        }
    }
}
