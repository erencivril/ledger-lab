# 0008. The risk engine is pure domain code and its rules are data

Status: accepted.

## Context

Some payments should not go through without a second look: a large first payment to a stranger, an amount far above what the sender normally pays, a payee account that was opened an hour ago. This is a risk check that runs before money moves. Three choices shape it: where the logic lives, how rules are written, and how a decision can be explained afterwards.

## Decision

- **Where.** `RiskEngine`, the rules and the decision types live in `domain.risk`. They take a `RiskContext` (plain values already collected) and return a `RiskDecision`. They never read a database or Redis. The application layer (`LedgerRiskAssessor`) gathers the facts. This keeps the rules testable with plain data and lets mutation testing cover them.
- **Shape.** A rule is a small class with `id()` and `evaluate(context)`. The engine runs the rules in order, collects the hits, and takes the strictest action. Two refinements: several weak hits can stack into a stronger action, and a step-up the customer has already confirmed counts as a warning. There is no scripting engine and no reflection. `RuleCatalog` maps a rule id to a class with a `switch`, so every available rule is visible in one place and an unknown id stops the start.
- **Rules as data.** Thresholds and actions are in YAML (`src/main/resources/risk/`), loaded into PostgreSQL (`risk_rule_sets`, `risk_rules`, `risk_rule_params`) at start. A published version is immutable: the stored SHA-256 of the file must match, or the application refuses to start. A change is a new version.
- **Audit.** Every decision is written to `risk_decisions` with the rule set version, plus one `risk_decision_hits` row per rule that fired. These tables are append-only; a trigger rejects UPDATE and DELETE.

## Consequences

- The engine runs before the money transaction, so facts it read can change before the transfer commits (a second payment may land in between). For a risk check that is acceptable; the ledger invariants do not depend on it.
- Rules are heuristics. They are not learned from data and they will be wrong in both directions. The back-test (added later) measures that on synthetic data and says so.
- Changing a threshold means a new file and a new version, not an edit. That is slower on purpose.

## Evidence

`RiskEngineSpec`, `RulesSpec`, `RuleCatalogSpec` (pure unit specs, covered by PIT), `RuleSetYamlSpec`, and `RiskHoldIntegrationSpec` for the stored versions, the immutability check and the append-only audit tables.
