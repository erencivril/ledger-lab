package dev.erencivril.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A completed transfer. There is no status: a transfer is written in the same database transaction
 * as its journal and balance changes, so it either exists in full or not at all.
 *
 * @param fxRate the rate applied, or {@code null} when no conversion happened
 */
public record Transfer(
        UUID id,
        TransferKind kind,
        UUID sourceAccountId,
        UUID destinationAccountId,
        Money sourceAmount,
        Money destinationAmount,
        BigDecimal fxRate,
        Instant createdAt) {}
