package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.ApplicationException.AccountNotFound;
import dev.erencivril.ledger.domain.Account;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Small helpers the three strategies share. */
final class AccountOrder {

    private AccountOrder() {}

    /**
     * The ids in ascending order, each one once. Every strategy touches its rows in this order, so two
     * transfers that use the same accounts in opposite directions queue on the same first row instead of
     * each holding one row and waiting for the other (a deadlock).
     */
    static List<UUID> ascending(UUID first, UUID second) {
        return Stream.of(first, second).distinct().sorted().toList();
    }

    static Account requireFound(Map<UUID, Account> accounts, UUID id) {
        Account account = accounts.get(id);
        if (account == null) {
            throw new AccountNotFound(id);
        }
        return account;
    }
}
