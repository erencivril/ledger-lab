package dev.erencivril.ledger.domain;

import java.util.UUID;

/**
 * A business rule was broken. Each subtype carries a stable machine-readable {@link #code()}.
 * The hierarchy is sealed so the HTTP layer can map it with an exhaustive switch.
 */
public abstract sealed class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class InvalidAmount extends DomainException {
        public InvalidAmount(String message) {
            super("invalid_amount", message);
        }
    }

    public static final class UnsupportedCurrency extends DomainException {
        public UnsupportedCurrency(String code) {
            super("unsupported_currency", "Unsupported currency: " + code);
        }
    }

    public static final class CurrencyMismatch extends DomainException {
        public CurrencyMismatch(String message) {
            super("currency_mismatch", message);
        }
    }

    public static final class InvalidOwner extends DomainException {
        public InvalidOwner(String message) {
            super("invalid_owner", message);
        }
    }

    public static final class SameAccount extends DomainException {
        public SameAccount(UUID accountId) {
            super("same_account", "Source and destination are the same account: " + accountId);
        }
    }

    public static final class InsufficientFunds extends DomainException {
        public InsufficientFunds(UUID accountId, Money balance, Money requested) {
            super(
                    "insufficient_funds",
                    "Account " + accountId + " has " + balance + " but " + requested + " was requested");
        }
    }

    public static final class NoFxRate extends DomainException {
        public NoFxRate(Currency from, Currency to) {
            super("no_fx_rate", "No FX rate from " + from + " to " + to);
        }
    }

    /** Release or reject was asked for a transfer that is not waiting for a decision. */
    public static final class TransferNotPending extends DomainException {
        public TransferNotPending(UUID transferId, TransferStatus status) {
            super("transfer_not_pending", "Transfer " + transferId + " is " + status + ", not PENDING");
        }
    }

    /** Raised when a journal does not sum to zero. That is a bug in the caller, not a client error. */
    public static final class UnbalancedJournal extends DomainException {
        public UnbalancedJournal(Currency currency) {
            super("unbalanced_journal", "Journal does not sum to zero in " + currency);
        }
    }
}
