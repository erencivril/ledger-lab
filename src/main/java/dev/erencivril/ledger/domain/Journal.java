package dev.erencivril.ledger.domain;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The set of lines that one transaction posts. The central invariant of the ledger is enforced here:
 * lines must sum to exactly zero in every currency. An unbalanced journal cannot be constructed.
 */
public record Journal(List<JournalLine> lines) {

    public Journal {
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A journal needs at least one line");
        }
        Map<Currency, BigDecimal> totals = lines.stream()
                .collect(Collectors.groupingBy(
                        line -> line.amount().currency(),
                        () -> new EnumMap<>(Currency.class),
                        Collectors.reducing(BigDecimal.ZERO, line -> line.amount().amount(), BigDecimal::add)));
        totals.forEach((currency, total) -> {
            if (total.signum() != 0) {
                throw new DomainException.UnbalancedJournal(currency);
            }
        });
    }
}
