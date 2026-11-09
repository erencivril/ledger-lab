package dev.erencivril.ledger.domain.risk;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * The settings of one rule inside a rule set, as stored in the database: which rule, what action it asks
 * for when it fires, whether it is switched on, and its numeric thresholds by name.
 */
public record RuleConfig(String id, RiskAction action, boolean enabled, Map<String, BigDecimal> params) {

    public RuleConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        params = Map.copyOf(params);
        if (action == RiskAction.ALLOW) {
            throw new IllegalArgumentException("Rule " + id + ": a rule that fires cannot ask for ALLOW");
        }
    }

    public BigDecimal param(String name) {
        BigDecimal value = params.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Rule " + id + " needs the parameter '" + name + "'");
        }
        return value;
    }
}
