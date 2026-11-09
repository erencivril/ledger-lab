package dev.erencivril.ledger.domain.risk;

import java.util.List;

/**
 * The answer of the engine for one payment: the final action, every rule that fired, and the version of the
 * rule set that was used, so the decision can be explained later.
 */
public record RiskDecision(RiskAction action, List<RuleHit> hits, int ruleSetVersion) {

    public RiskDecision {
        hits = List.copyOf(hits);
    }

    /** The ids of the rules that fired, in the order the rules are evaluated. */
    public List<String> reasonCodes() {
        return hits.stream().map(RuleHit::ruleId).toList();
    }
}
