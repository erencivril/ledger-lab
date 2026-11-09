package dev.erencivril.ledger.application;

import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.FxRate;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.Transfer;
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
    }

    public interface TransferRepository {
        void insert(Transfer transfer);

        Optional<Transfer> find(UUID id);
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
