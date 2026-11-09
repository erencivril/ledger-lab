package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.Ports.NewRiskDecision;
import dev.erencivril.ledger.application.TransferService.TransferCommand;
import dev.erencivril.ledger.domain.risk.RiskContext;
import dev.erencivril.ledger.domain.risk.RiskDecision;
import java.time.Instant;
import java.util.UUID;

/** The facts that were gathered for one payment and the decision the engine took on them. */
public record RiskAssessment(RiskContext context, RiskDecision decision) {

    /** The audit row for this assessment. {@code transferId} is null for a payment that did not go ahead. */
    NewRiskDecision toRecord(TransferCommand command, UUID transferId, Instant at) {
        return new NewRiskDecision(
                UUID.randomUUID(),
                transferId,
                command.sourceAccountId(),
                command.destinationAccountId(),
                command.amount(),
                context.amountUsd(),
                decision,
                context.stepUpConfirmed(),
                context.firstPaymentToPayee(),
                context.payeeAccountAge().toSeconds(),
                at);
    }
}
