package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.TransferService.TransferCommand;
import java.util.Optional;

/** Decides what the risk checks say about a payment, before any money moves. */
public interface RiskAssessor {

    /** Empty when no assessment applies: risk checks are off, or an account does not exist (the transfer then fails as usual). */
    Optional<RiskAssessment> assess(TransferCommand command);

    /** No risk checks at all. Every payment goes ahead as it did before the engine existed. */
    static RiskAssessor disabled() {
        return command -> Optional.empty();
    }
}
