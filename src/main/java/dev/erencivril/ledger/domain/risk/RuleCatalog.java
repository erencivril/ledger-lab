package dev.erencivril.ledger.domain.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the settings of a {@link RuleSet} into rule objects. The mapping from rule id to class is a plain
 * switch on purpose: every available rule is visible here, and an unknown id stops the application at start
 * instead of being ignored.
 */
public final class RuleCatalog {

    private RuleCatalog() {}

    public static RiskEngine build(RuleSet ruleSet) {
        List<Rule> rules = new ArrayList<>();
        for (RuleConfig config : ruleSet.rules()) {
            if (config.enabled()) {
                rules.add(create(config));
            }
        }
        return new RiskEngine(ruleSet.version(), rules, ruleSet.stackWarnings(), ruleSet.stackStepUps());
    }

    private static Rule create(RuleConfig config) {
        return switch (config.id()) {
            case FirstPaymentLargeRule.ID ->
                new FirstPaymentLargeRule(config.action(), config.param("minAmountUsd"));
            case NewPayeeAccountRule.ID ->
                new NewPayeeAccountRule(
                        config.action(), hours(config.param("maxAgeHours")), config.param("minAmountUsd"));
            case AmountDeviationRule.ID ->
                new AmountDeviationRule(
                        config.action(), config.param("factor"), config.param("minHistory").intValueExact());
            case NameMismatchRule.ID -> new NameMismatchRule(config.action());
            case SanctionsRule.ID -> new SanctionsRule(config.action());
            case "VELOCITY_1H" -> new VelocityRule(config.action(), 1, config.param("maxCount").intValueExact(), config.param("maxAmountUsd"));
            case "VELOCITY_24H" -> new VelocityRule(config.action(), 24, config.param("maxCount").intValueExact(), config.param("maxAmountUsd"));
            default -> throw new IllegalArgumentException("Unknown rule id: " + config.id());
        };
    }

    private static Duration hours(BigDecimal hours) {
        return Duration.ofMinutes(hours.multiply(BigDecimal.valueOf(60)).longValueExact());
    }
}
