package dev.erencivril.ledger.application;

import java.util.UUID;

/** Failures that are not domain rules: missing things, key reuse, rate limiting, lost races, outages. */
public abstract sealed class ApplicationException extends RuntimeException {

    private final String code;

    protected ApplicationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class AccountNotFound extends ApplicationException {
        public AccountNotFound(UUID id) {
            super("account_not_found", "Account " + id + " does not exist");
        }
    }

    public static final class TransferNotFound extends ApplicationException {
        public TransferNotFound(UUID id) {
            super("transfer_not_found", "Transfer " + id + " does not exist");
        }
    }

    /** The Idempotency-Key was used before with a different request body. */
    public static final class IdempotencyKeyReused extends ApplicationException {
        public IdempotencyKeyReused(String key) {
            super(
                    "idempotency_key_reused",
                    "Idempotency-Key '" + key + "' was already used with a different request body");
        }
    }

    public static final class VelocityLimitExceeded extends ApplicationException {
        private final long retryAfterSeconds;

        public VelocityLimitExceeded(UUID accountId, int limit, long windowSeconds, long retryAfterSeconds) {
            super(
                    "velocity_limit_exceeded",
                    "Account " + accountId + " already made " + limit + " transfers in the last " + windowSeconds
                            + " seconds. Retry in " + retryAfterSeconds + " seconds");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /**
     * A competing transfer got in the way: the optimistic version check failed, or PostgreSQL aborted
     * the transaction because of a deadlock or a serialization failure. Nothing was written, and the
     * same request can safely be tried again.
     */
    public static final class ConcurrentUpdate extends ApplicationException {
        public ConcurrentUpdate(String message) {
            super("concurrent_update", message);
        }
    }

    /** PostgreSQL cannot be reached. Nothing was written, and the same request can be tried again later. */
    public static final class StorageUnavailable extends ApplicationException {
        public StorageUnavailable(Throwable cause) {
            super("storage_unavailable", "The database is not available right now");
            initCause(cause);
        }
    }
}
