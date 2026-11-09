package dev.erencivril.ledger.domain.risk;

import java.math.BigDecimal;

/** Sliding windows include this attempt, including attempts later refused. Amounts are in USD. */
public record VelocitySnapshot(boolean available, int count1h, BigDecimal amount1h, int count24h, BigDecimal amount24h) {
    public static VelocitySnapshot empty() {
        return new VelocitySnapshot(true, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }
    public static VelocitySnapshot unavailable() {
        return new VelocitySnapshot(false, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }
}
