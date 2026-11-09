package dev.erencivril.ledger.risk;

import dev.erencivril.ledger.application.Ports.StoredRuleSet;
import dev.erencivril.ledger.application.Ports.UnitOfWork;
import dev.erencivril.ledger.domain.risk.RiskEngine;
import dev.erencivril.ledger.domain.risk.RuleCatalog;
import dev.erencivril.ledger.domain.risk.RuleSet;
import dev.erencivril.ledger.risk.RuleSetYaml.Parsed;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the rule sets that ship with the application (YAML files on the classpath) into the database, and
 * builds the engine from the stored copy.
 *
 * <p>A published version never changes. If a version is already in the database with a different file hash,
 * the application refuses to start: the fix is a new version file, not an edit.
 */
public final class RuleSetLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleSetLoader.class);

    /** The bundled files, oldest first. Adding a version means adding a file and a line here. */
    static final List<String> BUNDLED = List.of("risk/ruleset-v1.yaml");

    private final UnitOfWork unitOfWork;

    public RuleSetLoader(UnitOfWork unitOfWork) {
        this.unitOfWork = unitOfWork;
    }

    /** Loads every bundled version that is not stored yet. Returns the versions in order. */
    public List<Integer> loadBundled() {
        List<Integer> versions = new ArrayList<>();
        for (String resource : BUNDLED) {
            versions.add(publish(RuleSetYaml.parse(read(resource)), resource));
        }
        return versions;
    }

    /**
     * Stores a parsed rule set if its version is new. If the version exists, its stored hash must match;
     * otherwise somebody edited a published file and we stop. {@code source} is only used in the message.
     */
    public int publish(Parsed parsed, String source) {
        RuleCatalog.build(parsed.ruleSet()); // stops here on an unknown rule id or a missing parameter
        int version = parsed.ruleSet().version();
        unitOfWork.execute(tx -> {
            tx.ruleSets().insertIfAbsent(parsed.ruleSet(), parsed.sha256());
            StoredRuleSet stored = tx.ruleSets().find(version).orElseThrow();
            if (!stored.contentSha256().equals(parsed.sha256())) {
                throw new IllegalStateException("Rule set version " + version
                        + " is already published with different content. Add a new version instead of editing "
                        + source);
            }
            return null;
        });
        return version;
    }

    /** Builds the engine for a version from what is stored in the database. */
    public RiskEngine engineFor(int version) {
        RuleSet stored = unitOfWork
                .execute(tx -> tx.ruleSets().find(version))
                .orElseThrow(() -> new IllegalArgumentException("Rule set version " + version + " does not exist"))
                .ruleSet();
        log.info("Risk rule set version {} loaded: {}", version, stored.description());
        return RuleCatalog.build(stored);
    }

    private static String read(String resource) {
        try (InputStream in = RuleSetLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }
}
