package dev.erencivril.ledger.http;

import dev.erencivril.ledger.application.Ports.StatementEntry;
import dev.erencivril.ledger.application.Ports.StatementPage;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.Transfer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The JSON shapes the API returns. Money is always a string so no client parses it as a float. */
final class Views {

    private Views() {}

    record MoneyView(String amount, String currency) {
        static MoneyView of(Money money) {
            return money == null ? null : new MoneyView(money.amount().toPlainString(), money.currency().name());
        }
    }

    record AccountView(UUID id, String owner, String currency, String balance, Instant createdAt) {
        static AccountView of(Account account) {
            return new AccountView(
                    account.id(),
                    account.owner(),
                    account.currency().name(),
                    account.balance().amount().toPlainString(),
                    account.createdAt());
        }
    }

    record TransferView(
            UUID id,
            String kind,
            UUID sourceAccountId,
            UUID destinationAccountId,
            MoneyView sourceAmount,
            MoneyView destinationAmount,
            String fxRate,
            Instant createdAt) {
        static TransferView of(Transfer transfer) {
            return new TransferView(
                    transfer.id(),
                    transfer.kind().name(),
                    transfer.sourceAccountId(),
                    transfer.destinationAccountId(),
                    MoneyView.of(transfer.sourceAmount()),
                    MoneyView.of(transfer.destinationAmount()),
                    transfer.fxRate() == null ? null : transfer.fxRate().toPlainString(),
                    transfer.createdAt());
        }
    }

    record EntryView(long id, UUID transferId, MoneyView amount, MoneyView balanceAfter, Instant createdAt) {
        static EntryView of(StatementEntry entry) {
            return new EntryView(
                    entry.id(),
                    entry.transferId(),
                    MoneyView.of(entry.amount()),
                    MoneyView.of(entry.balanceAfter()),
                    entry.createdAt());
        }
    }

    record StatementView(UUID accountId, List<EntryView> entries, Long nextCursor) {
        static StatementView of(UUID accountId, StatementPage page) {
            return new StatementView(
                    accountId, page.entries().stream().map(EntryView::of).toList(), page.nextCursor());
        }
    }

    record HealthView(String status, String postgres, String redis) {}
}
