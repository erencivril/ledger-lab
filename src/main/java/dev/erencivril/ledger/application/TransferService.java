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
import dev.erencivril.ledger.domain.risk.RiskAction;
import dev.erencivril.ledger.domain.risk.RiskDecision;
import dev.erencivril.ledger.domain.Transfer;
import dev.erencivril.ledger.domain.TransferKind;
import dev.erencivril.ledger.domain.TransferPlan;
import dev.erencivril.ledger.domain.TransferStatus;
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
 *   <li>Ask the risk checks. BLOCK and STEP_UP stop here with an error and nothing moves; the decision is
 *       still written to the audit table.
 *   <li>In one database transaction: claim the key, change both balances (the {@link TransferStrategy}
 *       decides how) or, for HOLD, park the money in a hold account ({@link HoldService}), write the
 *       transfer, the journal and the risk decision.
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
    private final RiskAssessor risk;
    private final HoldService holds;
    private final AtomicLong retries = new AtomicLong();

    /** A service without risk checks: every payment goes ahead. */
    public TransferService(
            UnitOfWork unitOfWork,
            TransferStrategy strategy,
            VelocityLimiter velocityLimiter,
            IdempotencyCache cache,
            Clock clock,
            RetryPolicy retryPolicy,
            Pause pause) {
        this(unitOfWork, strategy, velocityLimiter, cache, clock, retryPolicy, pause, RiskAssessor.disabled(), null);
    }

    public TransferService(
            UnitOfWork unitOfWork,
            TransferStrategy strategy,
            VelocityLimiter velocityLimiter,
            IdempotencyCache cache,
            Clock clock,
            RetryPolicy retryPolicy,
            Pause pause,
            RiskAssessor risk,
            HoldService holds) {
        this.unitOfWork = unitOfWork;
        this.strategy = strategy;
        this.velocityLimiter = velocityLimiter;
        this.cache = cache;
        this.clock = clock;
        this.retryPolicy = retryPolicy;
        this.pause = pause;
        this.risk = risk;
        this.holds = holds;
    }

    /** How many times an attempt was repeated because of a {@link ConcurrentUpdate}, since this service was created. */
    public long retries() {
        return retries.get();
    }

    /**
     * @param stepUpConfirmed the customer has passed the extra check the risk engine asked for earlier
     */
    public record TransferCommand(
            String idempotencyKey,
            UUID sourceAccountId,
            UUID destinationAccountId,
            Money amount,
            boolean stepUpConfirmed, String payeeName) {

        public TransferCommand {
            // UTF-8 replacement of unpaired surrogates would alias distinct supplied names.
            dev.erencivril.ledger.domain.Text.requireWellFormed(payeeName, "payeeName");
        }

        public TransferCommand(String idempotencyKey, UUID sourceAccountId, UUID destinationAccountId, Money amount) {
            this(idempotencyKey, sourceAccountId, destinationAccountId, amount, false, null);
        }

        /**
         * A hash of the request body. Two requests are "the same" when this matches, so the amount is
         * normalised first: 10 and 10.00 are the same request.
         */
        public TransferCommand(String idempotencyKey, UUID sourceAccountId, UUID destinationAccountId, Money amount,
                boolean stepUpConfirmed) {
            this(idempotencyKey, sourceAccountId, destinationAccountId, amount, stepUpConfirmed, null);
        }

        // Exact supplied name is identity: null, empty and whitespace are distinct. Confirmation
        // is identity too; a refused STEP_UP never claims the key, so confirmation can retry it.
        private static String framed(String value) {
            return value == null ? "-1:" : value.length() + ":" + value;
        }

        String fingerprint() {
            String canonical = framed(sourceAccountId.toString()) + framed(destinationAccountId.toString())
                    + framed(amount.amount().toPlainString()) + framed(amount.currency().name())
                    + framed(payeeName) + framed(Boolean.toString(stepUpConfirmed));
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * @param replayed true when this is the stored result of an earlier request with the same key
     * @param risk the risk decision behind the transfer, or null when risk checks were off
     */
    public record TransferResult(Transfer transfer, boolean replayed, RiskDecision risk) {}

    public TransferResult transfer(TransferCommand command) {
        String fingerprint = command.fingerprint();

        Optional<TransferResult> replay = findReplay(command.idempotencyKey(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        // Account identity/currency/kind are immutable. Check before Redis or audit writes;
        // balances and all planner rules are checked again inside the money transaction.
        unitOfWork.execute(tx -> {
            var source = tx.accounts().find(command.sourceAccountId())
                    .orElseThrow(() -> new ApplicationException.AccountNotFound(command.sourceAccountId()));
            var destination = tx.accounts().find(command.destinationAccountId())
                    .orElseThrow(() -> new ApplicationException.AccountNotFound(command.destinationAccountId()));
            dev.erencivril.ledger.domain.TransferPlanner.validate(source, destination, command.amount());
            return null;
        });
        velocityLimiter.acquire(command.sourceAccountId());

        RiskAssessment assessment = risk.assess(command).orElse(null);
        if (assessment != null) {
            refuseIfNeeded(command, assessment);
        }

        TransferResult result = executeWithRetries(command, fingerprint, assessment);
        cache.put(command.idempotencyKey(), fingerprint, result.transfer().id());
        return result;
    }

    /** BLOCK and STEP_UP stop the payment. The decision is kept in the audit table either way. */
    private void refuseIfNeeded(TransferCommand command, RiskAssessment assessment) {
        RiskDecision decision = assessment.decision();
        if (decision.action() != RiskAction.BLOCK && decision.action() != RiskAction.STEP_UP) {
            return;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        unitOfWork.execute(tx -> {
            tx.risk().insertDecision(assessment.toRecord(command, null, now));
            return null;
        });
        if (decision.action() == RiskAction.BLOCK) {
            throw new ApplicationException.TransferBlocked(decision.reasonCodes());
        }
        throw new ApplicationException.StepUpRequired(decision.reasonCodes());
    }

    /**
     * Runs the transfer transaction. When it loses a race it waits a random time and runs it again from
     * the start; the failed attempt rolled back, so the next one sees a clean slate.
     */
    private TransferResult executeWithRetries(TransferCommand command, String fingerprint, RiskAssessment assessment) {
        int attempt = 1;
        while (true) {
            try {
                return unitOfWork.execute(tx -> executeInTransaction(tx, command, fingerprint, assessment));
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

    /** The risk decision behind a transfer, if there is one. */
    public Optional<RiskDecision> riskOf(UUID transferId) {
        return unitOfWork.execute(tx -> tx.risk().findDecision(transferId));
    }

    private Optional<TransferResult> findReplay(String key, String fingerprint) {
        Optional<CachedKey> cached = cache.get(key);
        // Redis is only a hint. Even an existing transfer ID proves no ownership of this key.
        return unitOfWork.execute(tx -> tx.idempotency().find(key).map(record -> {
            if (cached.isPresent() && (!cached.get().fingerprint().equals(record.fingerprint())
                    || !cached.get().transferId().equals(record.transferId()))) {
                log.warn("Ignoring inconsistent idempotency cache hint for key {}", key);
            }
            return replayOf(tx, record, fingerprint);
        }));
    }

    private TransferResult executeInTransaction(
            LedgerTransaction tx, TransferCommand command, String fingerprint, RiskAssessment assessment) {
        if (!tx.idempotency().tryClaim(command.idempotencyKey(), fingerprint)) {
            // Another request committed this key between our lookup and now.
            IdempotencyRecord record = tx.idempotency()
                    .find(command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("Idempotency key vanished after conflict"));
            return replayOf(tx, record, fingerprint);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean hold = assessment != null && assessment.decision().action() == RiskAction.HOLD;
        Posting posting = hold ? holds.place(tx, command, now) : moveMoney(tx, command, now);
        Transfer transfer = posting.transfer();

        tx.transfers().insert(transfer);
        tx.journal().append(transfer.id(), posting.entries(), now);
        tx.idempotency().attach(command.idempotencyKey(), transfer.id());
        if (assessment != null) {
            tx.risk().insertDecision(assessment.toRecord(command, transfer.id(), now));
        }
        return new TransferResult(transfer, false, assessment == null ? null : assessment.decision());
    }

    /** The ordinary path: the {@link TransferStrategy} changes both balances. */
    private Posting moveMoney(LedgerTransaction tx, TransferCommand command, Instant now) {
        BalanceChange change = strategy.changeBalances(
                tx, command.sourceAccountId(), command.destinationAccountId(), command.amount());
        TransferPlan plan = change.plan();
        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                TransferKind.TRANSFER,
                command.sourceAccountId(),
                command.destinationAccountId(),
                plan.sourceAmount(),
                plan.destinationAmount(),
                plan.fxRate(),
                now,
                TransferStatus.COMPLETED);
        List<NewJournalEntry> entries = plan.journal().lines().stream()
                .map(line -> new NewJournalEntry(line.accountId(), line.amount(), balanceAfter(line.accountId(), command, change)))
                .toList();
        return new Posting(transfer, entries);
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
        return replayResult(tx, transfer);
    }

    private static TransferResult replayResult(LedgerTransaction tx, Transfer transfer) {
        return new TransferResult(transfer, true, tx.risk().findDecision(transfer.id()).orElse(null));
    }

    private static void requireSameRequest(String key, String expected, String stored) {
        if (!expected.equals(stored)) {
            throw new IdempotencyKeyReused(key);
        }
    }
}
