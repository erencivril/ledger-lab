package dev.erencivril.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A single-currency account. Immutable: debit and credit return a new instance. */
public record Account(UUID id, String owner, Currency currency, AccountKind kind, Money balance, Instant createdAt) {

    public static final int MAX_OWNER_LENGTH = 100;

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(createdAt, "createdAt");
        if (balance.currency() != currency) {
            throw new IllegalArgumentException("Balance currency " + balance.currency() + " differs from " + currency);
        }
    }

    /** Creates a new customer account with a zero balance. */
    public static Account open(UUID id, String owner, Currency currency, Instant now) {
        if (owner == null || owner.isBlank()) {
            throw new DomainException.InvalidOwner("owner must not be blank");
        }
        if (owner.length() > MAX_OWNER_LENGTH) {
            throw new DomainException.InvalidOwner("owner must be at most " + MAX_OWNER_LENGTH + " characters");
        }
        return new Account(id, owner.strip(), currency, AccountKind.CUSTOMER, Money.zero(currency), now);
    }

    public Account debit(Money amount) {
        if (balance.compareTo(amount) < 0) {
            throw new DomainException.InsufficientFunds(id, balance, amount);
        }
        return withBalance(balance.minus(amount));
    }

    public Account credit(Money amount) {
        return withBalance(balance.plus(amount));
    }

    private Account withBalance(Money newBalance) {
        return new Account(id, owner, currency, kind, newBalance, createdAt);
    }
}
