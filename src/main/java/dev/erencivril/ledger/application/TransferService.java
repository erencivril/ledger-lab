package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.ApplicationException.AccountNotFound;
import dev.erencivril.ledger.application.ApplicationException.IdempotencyKeyReused;
import dev.erencivril.ledger.application.Ports.CachedKey;
import dev.erencivril.ledger.application.Ports.IdempotencyCache;
import dev.erencivril.ledger.application.Ports.IdempotencyRecord;
import dev.erencivril.ledger.application.Ports.LedgerTransaction;
import dev.erencivril.ledger.application.Ports.NewJournalEntry;
import dev.erencivril.ledger.application.Ports.UnitOfWork;
import dev.erencivril.ledger.application.Ports.VelocityLimiter;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.Transfer;
import dev.erencivril.ledger.domain.TransferKind;
import dev.erencivril.ledger.domain.TransferPlan;
import dev.erencivril.ledger.domain.TransferPlanner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
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
 *   <li>In one database transaction: claim the key, lock both accounts in id order, plan, write.
 * </ol>
 */
public final class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final UnitOfWork unitOfWork;
    private final TransferPlanner planner;
    private final VelocityLimiter velocityLimiter;
    private final IdempotencyCache cache;
    private final Clock clock;

    public TransferService(
            UnitOfWork unitOfWork,
            TransferPlanner planner,
            VelocityLimiter velocityLimiter,
            IdempotencyCache cache,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.planner = planner;
        this.velocityLimiter = velocityLimiter;
        this.cache = cache;
        this.clock = clock;
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

        TransferResult result = unitOfWork.execute(tx -> executeInTransaction(tx, command, fingerprint));
        cache.put(command.idempotencyKey(), fingerprint, result.transfer().id());
        return result;
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

        Map<UUID, Account> locked = lockInIdOrder(tx, command.sourceAccountId(), command.destinationAccountId());
        Account source = requireFound(locked, command.sourceAccountId());
        Account destination = requireFound(locked, command.destinationAccountId());

        TransferPlan plan = planner.plan(source, destination, command.amount());

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                TransferKind.TRANSFER,
                source.id(),
                destination.id(),
                plan.sourceAmount(),
                plan.destinationAmount(),
                plan.fxRate(),
                now);
        tx.transfers().insert(transfer);
        tx.accounts().updateBalance(source.id(), plan.sourceBalanceAfter());
        tx.accounts().updateBalance(destination.id(), plan.destinationBalanceAfter());

        List<NewJournalEntry> entries = plan.journal().lines().stream()
                .map(line -> new NewJournalEntry(
                        line.accountId(), line.amount(), balanceAfter(line.accountId(), plan, source, destination)))
                .toList();
        tx.journal().append(transfer.id(), entries, now);
        tx.idempotency().attach(command.idempotencyKey(), transfer.id());
        return new TransferResult(transfer, false);
    }

    /**
     * Locks every involved account row with SELECT ... FOR UPDATE, always in ascending id order. Two
     * transfers that touch the same accounts in opposite directions therefore queue on the same first
     * row instead of each holding one lock and waiting for the other, which is how deadlocks happen.
     */
    private static Map<UUID, Account> lockInIdOrder(LedgerTransaction tx, UUID first, UUID second) {
        Map<UUID, Account> locked = new HashMap<>();
        Stream.of(first, second).distinct().sorted().forEach(id -> tx.accounts()
                .findForUpdate(id)
                .ifPresent(account -> locked.put(id, account)));
        return locked;
    }

    private static Account requireFound(Map<UUID, Account> accounts, UUID id) {
        Account account = accounts.get(id);
        if (account == null) {
            throw new AccountNotFound(id);
        }
        return account;
    }

    private static Money balanceAfter(UUID accountId, TransferPlan plan, Account source, Account destination) {
        if (accountId.equals(source.id())) {
            return plan.sourceBalanceAfter();
        }
        if (accountId.equals(destination.id())) {
            return plan.destinationBalanceAfter();
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
