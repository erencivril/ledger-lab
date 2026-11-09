package dev.erencivril.ledger.persistence;

import static dev.erencivril.ledger.persistence.jooq.Tables.RISK_RULES;
import static dev.erencivril.ledger.persistence.jooq.Tables.RISK_RULE_PARAMS;
import static dev.erencivril.ledger.persistence.jooq.Tables.RISK_RULE_SETS;

import dev.erencivril.ledger.application.Ports.RuleSetRepository;
import dev.erencivril.ledger.application.Ports.StoredRuleSet;
import dev.erencivril.ledger.domain.risk.RiskAction;
import dev.erencivril.ledger.domain.risk.RuleConfig;
import dev.erencivril.ledger.domain.risk.RuleSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;

final class JooqRuleSetRepository implements RuleSetRepository {

    private final DSLContext dsl;

    JooqRuleSetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<StoredRuleSet> find(int version) {
        return dsl.selectFrom(RISK_RULE_SETS)
                .where(RISK_RULE_SETS.VERSION.eq(version))
                .fetchOptional()
                .map(set -> new StoredRuleSet(
                        new RuleSet(
                                version,
                                set.getDescription(),
                                set.getStackWarnings(),
                                set.getStackStepUps(),
                                rulesOf(version)),
                        set.getContentSha256()));
    }

    private List<RuleConfig> rulesOf(int version) {
        Map<String, Map<String, BigDecimal>> params = new LinkedHashMap<>();
        dsl.selectFrom(RISK_RULE_PARAMS)
                .where(RISK_RULE_PARAMS.RULE_SET_VERSION.eq(version))
                .forEach(p -> params.computeIfAbsent(p.getRuleId(), id -> new LinkedHashMap<>())
                        .put(p.getName(), p.getValue()));
        List<RuleConfig> rules = new ArrayList<>();
        dsl.selectFrom(RISK_RULES)
                .where(RISK_RULES.RULE_SET_VERSION.eq(version))
                .orderBy(RISK_RULES.POSITION)
                .forEach(r -> rules.add(new RuleConfig(
                        r.getRuleId(),
                        RiskAction.valueOf(r.getAction()),
                        r.getEnabled(),
                        params.getOrDefault(r.getRuleId(), Map.of()))));
        return rules;
    }

    @Override
    public void insertIfAbsent(RuleSet ruleSet, String contentSha256) {
        int inserted = dsl.insertInto(RISK_RULE_SETS)
                .set(RISK_RULE_SETS.VERSION, ruleSet.version())
                .set(RISK_RULE_SETS.DESCRIPTION, ruleSet.description())
                .set(RISK_RULE_SETS.CONTENT_SHA256, contentSha256)
                .set(RISK_RULE_SETS.STACK_WARNINGS, ruleSet.stackWarnings())
                .set(RISK_RULE_SETS.STACK_STEP_UPS, ruleSet.stackStepUps())
                .onConflictDoNothing()
                .execute();
        if (inserted == 0) {
            return;
        }
        int position = 1;
        for (RuleConfig rule : ruleSet.rules()) {
            dsl.insertInto(RISK_RULES)
                    .set(RISK_RULES.RULE_SET_VERSION, ruleSet.version())
                    .set(RISK_RULES.RULE_ID, rule.id())
                    .set(RISK_RULES.POSITION, position++)
                    .set(RISK_RULES.ACTION, rule.action().name())
                    .set(RISK_RULES.ENABLED, rule.enabled())
                    .execute();
            rule.params().forEach((name, value) -> dsl.insertInto(RISK_RULE_PARAMS)
                    .set(RISK_RULE_PARAMS.RULE_SET_VERSION, ruleSet.version())
                    .set(RISK_RULE_PARAMS.RULE_ID, rule.id())
                    .set(RISK_RULE_PARAMS.NAME, name)
                    .set(RISK_RULE_PARAMS.VALUE, value)
                    .execute());
        }
    }
}
