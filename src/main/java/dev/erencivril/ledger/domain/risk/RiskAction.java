package dev.erencivril.ledger.domain.risk;

/**
 * What the risk engine tells the payment to do. The constants are in order of severity, so
 * {@code compareTo} says which of two actions is stricter.
 */
public enum RiskAction {
    /** Nothing suspicious. The transfer goes ahead. */
    ALLOW,
    /** The transfer goes ahead, but the customer is shown a warning and the decision is kept for review. */
    WARN,
    /** The transfer waits until the customer confirms with something extra (a stand-in for a second factor). */
    STEP_UP,
    /** The money leaves the sender but is parked in a hold account until a person releases or rejects it. */
    HOLD,
    /** The transfer is refused. */
    BLOCK;

    public boolean atLeast(RiskAction other) {
        return compareTo(other) >= 0;
    }

    /** The stricter of the two. */
    public RiskAction orStricter(RiskAction other) {
        return atLeast(other) ? this : other;
    }
}
