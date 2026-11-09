package dev.erencivril.ledger.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.erencivril.ledger.domain.risk.RiskAction;
import dev.erencivril.ledger.domain.risk.RuleConfig;
import dev.erencivril.ledger.domain.risk.RuleSet;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a rule set from YAML. The file looks like this:
 *
 * <pre>
 * version: 2
 * description: Adds payee and velocity signals
 * stack: {warnings: 2, stepUps: 2}
 * rules:
 *   - id: FIRST_PAYMENT_LARGE
 *     action: WARN
 *     params: {minAmountUsd: 500}
 * </pre>
 *
 * Unknown keys are errors, so a typo such as "actoin" stops the start instead of being ignored.
 */
public final class RuleSetYaml {

    /** A parsed rule set and the SHA-256 of the text it was read from. */
    public record Parsed(RuleSet ruleSet, String sha256) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private RuleSetYaml() {}

    public static Parsed parse(String text) {
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Rule set is not valid YAML: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Rule set must be a YAML mapping");
        }
        onlyKeys(root, Set.of("version", "description", "stack", "rules"), "the rule set");

        JsonNode stack = root.path("stack");
        if (!stack.isMissingNode()) {
            onlyKeys(stack, Set.of("warnings", "stepUps"), "'stack'");
        }
        List<RuleConfig> rules = new ArrayList<>();
        JsonNode ruleNodes = root.path("rules");
        if (!ruleNodes.isArray()) {
            throw new IllegalArgumentException("'rules' must be a list");
        }
        for (JsonNode node : ruleNodes) {
            rules.add(rule(node));
        }
        RuleSet ruleSet = new RuleSet(
                intOf(root, "version", true),
                textOf(root, "description"),
                stack.isMissingNode() ? 0 : intOf(stack, "warnings", false),
                stack.isMissingNode() ? 0 : intOf(stack, "stepUps", false),
                rules);
        return new Parsed(ruleSet, sha256(text));
    }

    private static RuleConfig rule(JsonNode node) {
        onlyKeys(node, Set.of("id", "action", "enabled", "params"), "a rule");
        String id = textOf(node, "id");
        RiskAction action;
        try {
            action = RiskAction.valueOf(textOf(node, "action"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Rule " + id + ": unknown action '" + node.path("action").asText() + "'");
        }
        boolean enabled = !node.has("enabled") || node.get("enabled").asBoolean(true);
        Map<String, BigDecimal> params = new LinkedHashMap<>();
        JsonNode paramNodes = node.path("params");
        if (!paramNodes.isMissingNode() && !paramNodes.isObject()) {
            throw new IllegalArgumentException("Rule " + id + ": 'params' must be a mapping");
        }
        paramNodes.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNumber()) {
                throw new IllegalArgumentException("Rule " + id + ": parameter '" + entry.getKey() + "' must be a number");
            }
            params.put(entry.getKey(), entry.getValue().decimalValue());
        });
        return new RuleConfig(id, action, enabled, params);
    }

    private static void onlyKeys(JsonNode node, Set<String> allowed, String where) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(where + " must be a mapping");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown key '" + name + "' in " + where);
            }
        });
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("'" + field + "' is required and must be text");
        }
        return value.asText();
    }

    private static int intOf(JsonNode node, String field, boolean required) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() && !required) {
            return 0;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("'" + field + "' is required and must be a whole number");
        }
        return value.intValue();
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
