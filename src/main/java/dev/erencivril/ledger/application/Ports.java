package dev.erencivril.ledger.application;

import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.FxRate;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.Transfer;
import dev.erencivril.ledger.domain.TransferStatus;
import dev.erencivril.ledger.domain.risk.RiskDecision;
import dev.erencivril.ledger.domain.risk.RuleSet;
import dev.erencivril.ledger.domain.risk.VelocitySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** The interfaces the application layer needs from the outside world. Adapters implement them. */
public final class Ports {

    private Ports() {}

    /** Runs work in one database transaction. Any exception rolls the whole transaction back. */
    public interface UnitOfWork {
        <T> T execute(Function<LedgerTransaction, T> work);
    }

    /** The repositories, all bound to the same open transaction. */
    public interface LedgerTransaction {
        AccountRepository accounts();

        TransferRepository transfers();

        JournalRepository journal();

        IdempotencyRepository idempotency();

        FxRateRepository fxRates();

        RiskRepository risk();

        RuleSetRepository ruleSets();
    }

    public interface AccountRepository {
        void insert(Account account);

        /** Customer accounts only. Treasury accounts are never visible through the API. */
        Optional<Account> find(UUID id);

        /** SELECT ... FOR UPDATE. Holds the row lock until the transaction ends. */
        Optional<Account> findForUpdate(UUID id);

        void updateBalance(UUID id, Money balance);

        /** Plain SELECT, no lock. For the optimistic strategy. */
        Optional<VersionedAccount> findVersioned(UUID id);

        /**
         * UPDATE ... WHERE id = ? AND version = ?, which also adds one to the version. Returns false when no
         * row matched, meaning somebody changed the account after it was read.
         */
        boolean updateBalanceIfVersion(UUID id, Money balance, long expectedVersion);

        /**
         * UPDATE ... SET balance = balance - ? WHERE id = ? AND balance >= ? RETURNING balance. Empty when
         * the account holds less than the amount.
         */
        Optional<Money> debitIfFunded(UUID id, Money amount);

        /** UPDATE ... SET balance = balance + ? WHERE id = ? RETURNING balance. Returns the new balance. */
        Money credit(UUID id, Money amount);

        Map<Currency, UUID> treasuryIds();

        Map<Currency, UUID> holdIds();
    }

    public interface TransferRepository {
        void insert(Transfer transfer);

        Optional<Transfer> find(UUID id);

        /** SELECT ... FOR UPDATE. Two people deciding on the same held transfer take turns. */
        Optional<Transfer> findForUpdate(UUID id);

        /** Moves a PENDING transfer to its final status and records when. */
        void resolve(UUID id, TransferStatus status, Instant at);
    }

    public interface JournalRepository {
        void append(UUID transferId, List<NewJournalEntry> entries, Instant createdAt);

        /** Newest first. {@code before} is the id of the last entry of the previous page, or null. */
        StatementPage statement(UUID accountId, int limit, Long before);
    }

    public interface IdempotencyRepository {
        /** Inserts the key if it is new. Returns false if it already exists (committed by someone else). */
        boolean tryClaim(String key, String fingerprint);

        Optional<IdempotencyRecord> find(String key);

        void attach(String key, UUID transferId);
    }

    public interface FxRateRepository {
        List<FxRate> findAll();
    }

    /** What the risk engine needs to know about the payee, read from the ledger. */
    public record PayeeFacts(String ownerName, Instant openedAt, boolean paidBefore) {}

    /** How much the sender has paid recently. {@code averageAmount} is in the sender's currency, null when {@code count} is 0. */
    public record SenderHistory(int count, BigDecimal averageAmount) {}

    /** One row of the risk_decisions audit table. {@code transferId} is null when no transfer was created. */
    public record NewRiskDecision(
            UUID id,
            UUID transferId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            Money amount,
            BigDecimal amountUsd,
            RiskDecision decision,
            boolean stepUpConfirmed,
            boolean firstPayment,
            long payeeAgeSeconds,
            Instant createdAt) {}

    public interface RiskRepository {
        /** Empty when the payee account does not exist (the transfer will then fail with a normal 404). */
        Optional<PayeeFacts> payeeFacts(UUID senderId, UUID payeeId);

        /** Completed payments by the sender since {@code since}. */
        SenderHistory senderHistory(UUID senderId, Instant since);

        void insertDecision(NewRiskDecision decision);

        Optional<RiskDecision> findDecision(UUID transferId);
    }

    /** A rule set as stored, with the hash of the file it was loaded from. */
    public record StoredRuleSet(RuleSet ruleSet, String contentSha256) {}

    public interface RuleSetRepository {
        Optional<StoredRuleSet> find(int version);

        /** Does nothing if the version exists already. */
        void insertIfAbsent(RuleSet ruleSet, String contentSha256);
    }

    /** A best-effort signal store, never a money store. Unavailable means HOLD, not a zero count. */
    public interface RiskVelocity {
        VelocitySnapshot record(UUID senderId, BigDecimal amountUsd);
        static RiskVelocity empty() { return (sender, amount) -> VelocitySnapshot.empty(); }
    }

    /** An account together with the version it had when it was read. */
    public record VersionedAccount(Account account, long version) {}

    public record NewJournalEntry(UUID accountId, Money amount, Money balanceAfter) {}

    public record StatementEntry(
            long id, UUID transferId, Money amount, Money balanceAfter, Instant createdAt) {}

    /** @param nextCursor pass as {@code before} to get the next page, or null when this is the last page */
    public record StatementPage(List<StatementEntry> entries, Long nextCursor) {}

    public record IdempotencyRecord(String key, String fingerprint, UUID transferId) {}

    /** The risk rule. Throws {@link ApplicationException.VelocityLimitExceeded} when the limit is hit. */
    public interface VelocityLimiter {
        void acquire(UUID sourceAccountId);

        static VelocityLimiter unlimited() {
            return id -> {};
        }
    }

    /** A cache in front of the idempotency table. It may be empty, stale or down; Postgres decides. */
    public interface IdempotencyCache {
        Optional<CachedKey> get(String key);

        void put(String key, String fingerprint, UUID transferId);

        static IdempotencyCache none() {
            return new IdempotencyCache() {
                @Override
                public Optional<CachedKey> get(String key) {
                    return Optional.empty();
                }

                @Override
                public void put(String key, String fingerprint, UUID transferId) {}
            };
        }
    }

    public record CachedKey(String fingerprint, UUID transferId) {}

    /** Waits between two attempts of a transfer. A port so that tests can see the waits without sitting through them. */
    public interface Pause {
        void pause(long millis);

        static Pause sleeping() {
            return millis -> {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to retry a transfer", e);
                }
            };
        }
    }

    public interface HealthProbe {
        boolean isUp();
    }
}
