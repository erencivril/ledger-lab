package dev.erencivril.ledger.domain.risk;

import java.util.Optional;

public record NameMismatchRule(RiskAction action) implements Rule {
    public static final String ID = "NAME_NO_MATCH";
    public String id() { return ID; }
    public Optional<RuleHit> evaluate(RiskContext context) {
        return context.nameCheck().grade() == NameMatcher.Grade.NO_MATCH
                ? Optional.of(new RuleHit(ID, action, "supplied payee name does not match the account owner"))
                : Optional.empty();
    }
}
