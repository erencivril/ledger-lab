package dev.erencivril.ledger.domain.risk;

import java.util.Optional;

/**
 * One small check. It looks at the context and either fires (returns a {@link RuleHit}) or stays quiet. A
 * rule never decides the final outcome on its own; the {@link RiskEngine} combines the hits.
 */
public interface Rule {

    /** The rule id, which is also its reason code. */
    String id();

    Optional<RuleHit> evaluate(RiskContext context);
}
