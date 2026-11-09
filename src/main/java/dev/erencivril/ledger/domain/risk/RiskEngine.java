package dev.erencivril.ledger.domain.risk;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the rules of one rule set, in order, and turns their hits into a single {@link RiskDecision}.
 *
 * <p>The decision is the strictest action among the hits, with two adjustments:
 * <ol>
 *   <li>Stacking: several weak hits together count as a stronger one. {@code stackWarnings} WARN hits
 *       become STEP_UP, and {@code stackStepUps} STEP_UP hits become HOLD. A limit of 0 turns that off.
 *   <li>If the customer already passed the extra check ({@code stepUpConfirmed}), a STEP_UP becomes a
 *       WARN. HOLD and BLOCK are not affected: confirming does not talk the engine out of them.
 * </ol>
 */
public final class RiskEngine {

    private final int ruleSetVersion;
    private final List<Rule> rules;
    private final int stackWarnings;
    private final int stackStepUps;

    public RiskEngine(int ruleSetVersion, List<Rule> rules, int stackWarnings, int stackStepUps) {
        this.ruleSetVersion = ruleSetVersion;
        this.rules = List.copyOf(rules);
        this.stackWarnings = stackWarnings;
        this.stackStepUps = stackStepUps;
    }

    public int ruleSetVersion() {
        return ruleSetVersion;
    }

    public RiskDecision assess(RiskContext context) {
        List<RuleHit> hits = new ArrayList<>();
        if (!context.velocity().available()) {
            hits.add(new RuleHit("VELOCITY_UNAVAILABLE", RiskAction.HOLD, "velocity store unavailable; manual review required"));
        }
        for (Rule rule : rules) {
            rule.evaluate(context).ifPresent(hits::add);
        }
        return new RiskDecision(decide(hits, context.stepUpConfirmed()), hits, ruleSetVersion);
    }

    private RiskAction decide(List<RuleHit> hits, boolean stepUpConfirmed) {
        RiskAction action = RiskAction.ALLOW;
        for (RuleHit hit : hits) {
            action = action.orStricter(hit.action());
        }
        if (!action.atLeast(RiskAction.HOLD)) {
            action = stack(action, hits);
        }
        if (action == RiskAction.STEP_UP && stepUpConfirmed) {
            return RiskAction.WARN;
        }
        return action;
    }

    private RiskAction stack(RiskAction action, List<RuleHit> hits) {
        if (action == RiskAction.STEP_UP && stackStepUps > 0 && countOf(RiskAction.STEP_UP, hits) >= stackStepUps) {
            return RiskAction.HOLD;
        }
        // Here every hit is a WARN, so the number of hits is the number of warnings.
        if (action == RiskAction.WARN && stackWarnings > 0 && hits.size() >= stackWarnings) {
            return RiskAction.STEP_UP;
        }
        return action;
    }

    private static long countOf(RiskAction action, List<RuleHit> hits) {
        return hits.stream().filter(hit -> hit.action() == action).count();
    }
}
