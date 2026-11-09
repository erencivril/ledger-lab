package dev.erencivril.ledger.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Checks a whole ledger against its own rules. It is pure logic: the caller reads the rows from the
 * database and hands them over, and gets back a list of problems. An empty list means the ledger is sound.
 *
 * <p>The rules, each one reported separately:
 * <ol>
 *   <li>Every transfer sums to zero in every currency.
 *   <li>Replaying the journal from the first entry, a customer account is never negative.
 *   <li>The balance recorded on each journal entry equals the replayed balance at that entry.
 *   <li>The stored balance of each customer account equals the replayed balance at the end.
 *   <li>Every journal entry belongs to an account that exists.
 *   <li>In each currency, the hold accounts together hold exactly what the PENDING transfers took from
 *       their senders: no more (money nobody can claim) and no less (money that was spent twice).
 * </ol>
 *
 * <p>The stress tests and the benchmark run this after every run, so it is the one definition of
 * "the ledger is correct".
 */
public final class LedgerAudit {

    private LedgerAudit() {}

    /** @param storedBalance the balance column of a customer account, or null for a treasury account */
    public record AccountRow(UUID id, AccountKind kind, BigDecimal storedBalance) {}

    /** @param balanceAfter the balance recorded on the entry, or null (treasury entries have none) */
    public record EntryRow(
            long id, UUID transferId, UUID accountId, Currency currency, BigDecimal amount, BigDecimal balanceAfter) {}

    /** The ledger audit when no transfer is waiting on a hold. */
    public static List<String> violations(List<AccountRow> accounts, List<EntryRow> entries) {
        return violations(accounts, entries, Map.of());
    }

    /** @param pendingByCurrency the total source amount of the PENDING transfers, per currency */
    public static List<String> violations(
            List<AccountRow> accounts, List<EntryRow> entries, Map<Currency, BigDecimal> pendingByCurrency) {
        List<String> problems = new ArrayList<>();
        Map<UUID, AccountRow> accountsById = new HashMap<>();
        for (AccountRow account : accounts) {
            accountsById.put(account.id(), account);
        }

        // Replay in the order the entries were written. The entry id is the order.
        List<EntryRow> inOrder = new ArrayList<>(entries);
        inOrder.sort(Comparator.comparingLong(EntryRow::id));

        checkEveryTransferBalances(inOrder, problems);
        Map<UUID, BigDecimal> replayed = replay(inOrder, accountsById, problems);
        checkStoredBalances(accounts, replayed, problems);
        checkHolds(inOrder, accountsById, pendingByCurrency, problems);
        return problems;
    }

    private static void checkHolds(
            List<EntryRow> inOrder,
            Map<UUID, AccountRow> accountsById,
            Map<Currency, BigDecimal> pendingByCurrency,
            List<String> problems) {
        Map<Currency, BigDecimal> held = new EnumMap<>(Currency.class);
        for (EntryRow entry : inOrder) {
            AccountRow account = accountsById.get(entry.accountId());
            if (account != null && account.kind() == AccountKind.HOLD) {
                held.merge(entry.currency(), entry.amount(), BigDecimal::add);
            }
        }
        for (Currency currency : Currency.values()) {
            BigDecimal inHold = held.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal pending = pendingByCurrency.getOrDefault(currency, BigDecimal.ZERO);
            if (inHold.compareTo(pending) != 0) {
                problems.add("hold accounts hold " + inHold.toPlainString() + " " + currency
                        + " but the pending transfers add up to " + pending.toPlainString() + " " + currency);
            }
        }
    }

    private static void checkEveryTransferBalances(List<EntryRow> inOrder, List<String> problems) {
        // The key is "transfer id + currency", because a cross-currency transfer balances once per currency.
        Map<String, BigDecimal> sums = new HashMap<>();
        for (EntryRow entry : inOrder) {
            String key = entry.transferId() + " " + entry.currency();
            sums.merge(key, entry.amount(), BigDecimal::add);
        }
        sums.forEach((key, sum) -> {
            if (sum.signum() != 0) {
                String[] parts = key.split(" ");
                problems.add("transfer " + parts[0] + " sums to " + sum.toPlainString() + " " + parts[1] + ", not zero");
            }
        });
    }

    /** Walks the entries once, keeping a running balance per account. Returns the final balance of each. */
    private static Map<UUID, BigDecimal> replay(
            List<EntryRow> inOrder, Map<UUID, AccountRow> accountsById, List<String> problems) {
        Map<UUID, BigDecimal> running = new HashMap<>();
        for (EntryRow entry : inOrder) {
            AccountRow account = accountsById.get(entry.accountId());
            if (account == null) {
                problems.add("entry " + entry.id() + " belongs to unknown account " + entry.accountId());
                continue;
            }
            BigDecimal balance = running.getOrDefault(entry.accountId(), BigDecimal.ZERO).add(entry.amount());
            running.put(entry.accountId(), balance);

            if (account.kind() == AccountKind.CUSTOMER) {
                if (balance.signum() < 0) {
                    problems.add("account " + account.id() + " is negative (" + balance.toPlainString()
                            + ") after entry " + entry.id());
                }
                if (entry.balanceAfter() != null && entry.balanceAfter().compareTo(balance) != 0) {
                    problems.add("entry " + entry.id() + " records balance_after " + entry.balanceAfter().toPlainString()
                            + " but the replay gives " + balance.toPlainString());
                }
            }
        }
        return running;
    }

    private static void checkStoredBalances(
            List<AccountRow> accounts, Map<UUID, BigDecimal> replayed, List<String> problems) {
        for (AccountRow account : accounts) {
            if (account.kind() != AccountKind.CUSTOMER) {
                continue;
            }
            BigDecimal fromJournal = replayed.getOrDefault(account.id(), BigDecimal.ZERO);
            if (account.storedBalance().compareTo(fromJournal) != 0) {
                problems.add("account " + account.id() + " stores " + account.storedBalance().toPlainString()
                        + " but its journal replays to " + fromJournal.toPlainString());
            }
        }
    }
}
