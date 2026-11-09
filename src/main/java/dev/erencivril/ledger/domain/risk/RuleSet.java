package dev.erencivril.ledger.domain.risk;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A numbered, immutable list of rule settings. Once a version is published it is never edited; a change
 * becomes a new version, so a decision can always be traced to the exact rules that made it.
 *
 * @param stackWarnings this many WARN hits together are treated as STEP_UP (0 switches stacking off)
 * @param stackStepUps this many STEP_UP hits together are treated as HOLD (0 switches stacking off)
 */
public record RuleSet(int version, String description, int stackWarnings, int stackStepUps, List<RuleConfig> rules) {

    public RuleSet {
        Objects.requireNonNull(description, "description");
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        if (stackWarnings < 0 || stackStepUps < 0) {
            throw new IllegalArgumentException("stacking limits must not be negative");
        }
        rules = List.copyOf(rules);
        Set<String> seen = new HashSet<>();
        for (RuleConfig rule : rules) {
            if (!seen.add(rule.id())) {
                throw new IllegalArgumentException("Rule " + rule.id() + " appears twice in version " + version);
            }
        }
    }
}
