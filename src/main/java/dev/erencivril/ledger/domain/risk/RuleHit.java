package dev.erencivril.ledger.domain.risk;

import java.util.Objects;

/**
 * One rule that fired. The rule id doubles as the reason code stored in the audit table.
 *
 * @param action what this rule alone asks for
 * @param detail a short sentence with the numbers behind the hit, for the person reading the audit
 */
public record RuleHit(String ruleId, RiskAction action, String detail) {

    public RuleHit {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(detail, "detail");
    }
}
