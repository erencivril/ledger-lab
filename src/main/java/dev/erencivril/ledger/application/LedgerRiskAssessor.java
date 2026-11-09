package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.Ports.PayeeFacts;
import dev.erencivril.ledger.application.Ports.SenderHistory;
import dev.erencivril.ledger.application.Ports.UnitOfWork;
import dev.erencivril.ledger.application.TransferService.TransferCommand;
import dev.erencivril.ledger.domain.FxRateTable;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.risk.RiskContext;
import dev.erencivril.ledger.domain.risk.RiskEngine;
import dev.erencivril.ledger.domain.risk.NameMatcher;
import dev.erencivril.ledger.domain.risk.MockSanctions;
import dev.erencivril.ledger.application.Ports.RiskVelocity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Collects the facts about a payment from the ledger and asks the {@link RiskEngine} what to do. The engine
 * is pure; this class is the part that reads.
 */
public final class LedgerRiskAssessor implements RiskAssessor {

    /** How far back the sender's "usual payment size" looks. */
    static final Duration HISTORY = Duration.ofDays(90);

    private final UnitOfWork unitOfWork;
    private final RiskEngine engine;
    private final FxRateTable rates;
    private final Clock clock;
    private final RiskVelocity velocity;

    public LedgerRiskAssessor(UnitOfWork unitOfWork, RiskEngine engine, FxRateTable rates, Clock clock) {
        this(unitOfWork, engine, rates, clock, RiskVelocity.empty());
    }

    public LedgerRiskAssessor(UnitOfWork unitOfWork, RiskEngine engine, FxRateTable rates, Clock clock, RiskVelocity velocity) {
        this.unitOfWork = unitOfWork;
        this.engine = engine;
        this.rates = rates;
        this.clock = clock;
        this.velocity = velocity;
    }

    @Override
    public Optional<RiskAssessment> assess(TransferCommand command) {
        return unitOfWork.execute(tx -> {
            Optional<PayeeFacts> payee =
                    tx.risk().payeeFacts(command.sourceAccountId(), command.destinationAccountId());
            if (payee.isEmpty()) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            SenderHistory history = tx.risk().senderHistory(command.sourceAccountId(), now.minus(HISTORY));
            Money amount = command.amount();
            BigDecimal averageUsd = history.averageAmount() == null
                    ? null
                    : rates.toUsd(Money.rounded(history.averageAmount(), amount.currency())).amount();
            Duration age = Duration.between(payee.get().openedAt(), now);
            RiskContext context = new RiskContext(
                    amount,
                    rates.toUsd(amount).amount(),
                    !payee.get().paidBefore(),
                    age.isNegative() ? Duration.ZERO : age,
                    history.count(),
                    averageUsd,
                    command.stepUpConfirmed(),
                    NameMatcher.check(command.payeeName(), payee.get().ownerName()),
                    MockSanctions.contains(payee.get().ownerName()),
                    velocity.record(command.sourceAccountId(), rates.toUsd(amount).amount()));
            return Optional.of(new RiskAssessment(context, engine.assess(context)));
        });
    }
}
