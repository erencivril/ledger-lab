package dev.erencivril.ledger.application;

import dev.erencivril.ledger.domain.TransferPlanner;
import java.util.Locale;

/** The names of the three strategies, as written in the LOCK_STRATEGY setting. */
public enum LockStrategyKind {
    PESSIMISTIC,
    OPTIMISTIC,
    ATOMIC;

    public TransferStrategy create(TransferPlanner planner) {
        return switch (this) {
            case PESSIMISTIC -> new PessimisticLockStrategy(planner);
            case OPTIMISTIC -> new OptimisticVersionStrategy(planner);
            case ATOMIC -> new AtomicUpdateStrategy(planner);
        };
    }

    /** Case-insensitive. An unknown name is an error, so a typo cannot silently switch strategy. */
    public static LockStrategyKind parse(String text) {
        String wanted = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (LockStrategyKind kind : values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return kind;
            }
        }
        throw new IllegalArgumentException(
                "Unknown lock strategy '" + text + "'. Choose one of: pessimistic, optimistic, atomic");
    }
}
