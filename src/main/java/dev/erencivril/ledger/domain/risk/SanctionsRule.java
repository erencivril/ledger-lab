package dev.erencivril.ledger.domain.risk;

import java.util.Optional;

public record SanctionsRule(RiskAction action) implements Rule {
    public static final String ID = "MOCK_SANCTIONS";
    public String id() { return ID; }
    public Optional<RuleHit> evaluate(RiskContext context) {
        return context.sanctioned()
                ? Optional.of(new RuleHit(ID, action, "account owner is on the invented mock sanctions list"))
                : Optional.empty();
    }
}
