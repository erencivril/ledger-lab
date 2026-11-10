package dev.erencivril.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded GBP examples, not a claim assessor or a regulatory compliance implementation.
 * The caller establishes scope, fraud, gross negligence and scam-related vulnerability.
 * See docs/reimbursement.md for the supported cases, exclusions and dated PSR sources.
 */
public final class ReimbursementCalculator {
    private static final BigDecimal CAP = new BigDecimal("85000.00");
    private static final BigDecimal MAX_EXCESS = new BigDecimal("100.00");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public enum Eligibility { ELIGIBLE, OUT_OF_SCOPE, FRAUDULENT, GROSS_NEGLIGENCE }

    /** Net costs after the sending PSP has paid the consumer and obtained the contribution. */
    public record Split(BigDecimal sendingPsp, BigDecimal receivingPsp) { }

    /** An absent split means settlement is not modelled, not that the receiving PSP owes zero. */
    public record Result(BigDecimal reimbursement, BigDecimal appliedExcess, Optional<Split> split) { }

    public Result calculate(BigDecimal loss, Eligibility eligibility, boolean vulnerable, BigDecimal chosenExcess) {
        loss = pounds(loss);
        chosenExcess = pounds(chosenExcess);
        Objects.requireNonNull(eligibility, "eligibility");
        if (chosenExcess.compareTo(MAX_EXCESS) > 0) {
            throw new IllegalArgumentException("Excess must not exceed GBP 100");
        }
        if (eligibility == Eligibility.OUT_OF_SCOPE || eligibility == Eligibility.FRAUDULENT
                || (eligibility == Eligibility.GROSS_NEGLIGENCE && !vulnerable)) {
            return new Result(ZERO, ZERO, Optional.empty());
        }
        BigDecimal excess = vulnerable ? ZERO : chosenExcess;
        // Do not infer the ordering of cap and excess when both affect the same claim.
        if (loss.compareTo(CAP) > 0 && excess.signum() > 0) {
            throw new IllegalArgumentException("Loss above the cap with a nonzero excess is not modelled");
        }
        BigDecimal appliedExcess = loss.min(excess);
        BigDecimal reimbursement = loss.subtract(appliedExcess).min(CAP);
        Optional<Split> split = Optional.empty();
        // Only one receiving PSP, no recovery, maximum excess, no voluntary top-up.
        // Reduced/waived excess and vulnerable settlements require more scheme detail.
        if (!vulnerable && chosenExcess.compareTo(MAX_EXCESS) == 0) {
            BigDecimal receiving = reimbursement.divide(BigDecimal.TWO, 2, RoundingMode.DOWN);
            split = Optional.of(new Split(reimbursement.subtract(receiving), receiving));
        }
        return new Result(reimbursement, appliedExcess, split);
    }

    private static BigDecimal pounds(BigDecimal value) {
        Objects.requireNonNull(value, "GBP amount");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("GBP amount must not be negative");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("GBP amount must be exact to a penny", e);
        }
    }
}
