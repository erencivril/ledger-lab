-- Risk engine data: versioned rule sets and an audit row for every decision.
--
-- A rule set version is written once and never changed. A change in the rules is a new version, so every
-- decision can be traced to the exact rules that made it.

CREATE TABLE risk_rule_sets (
    version        integer      PRIMARY KEY,
    description    varchar(200) NOT NULL,
    -- Hash of the YAML file the version was loaded from. A start-up check compares it with the file.
    content_sha256 varchar(64)  NOT NULL,
    stack_warnings integer      NOT NULL,
    stack_step_ups integer      NOT NULL,
    loaded_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT risk_rule_sets_version_chk CHECK (version >= 1),
    CONSTRAINT risk_rule_sets_stack_chk CHECK (stack_warnings >= 0 AND stack_step_ups >= 0)
);

CREATE TABLE risk_rules (
    rule_set_version integer     NOT NULL REFERENCES risk_rule_sets (version),
    rule_id          varchar(50) NOT NULL,
    -- The order the rules are evaluated in, and so the order of the reason codes.
    position         integer     NOT NULL,
    action           varchar(10) NOT NULL,
    enabled          boolean     NOT NULL,
    PRIMARY KEY (rule_set_version, rule_id),
    CONSTRAINT risk_rules_action_chk CHECK (action IN ('WARN', 'STEP_UP', 'HOLD', 'BLOCK'))
);

CREATE TABLE risk_rule_params (
    rule_set_version integer        NOT NULL,
    rule_id          varchar(50)    NOT NULL,
    name             varchar(50)    NOT NULL,
    value            numeric(20, 6) NOT NULL,
    PRIMARY KEY (rule_set_version, rule_id, name),
    FOREIGN KEY (rule_set_version, rule_id) REFERENCES risk_rules (rule_set_version, rule_id)
);

-- One row per payment that the engine looked at. transfer_id is empty when the payment did not go
-- ahead (BLOCK, or STEP_UP that was not confirmed).
CREATE TABLE risk_decisions (
    id                     uuid PRIMARY KEY,
    transfer_id            uuid           REFERENCES transfers (id),
    source_account_id      uuid           NOT NULL REFERENCES accounts (id),
    destination_account_id uuid           NOT NULL REFERENCES accounts (id),
    amount                 numeric(30, 3) NOT NULL,
    currency               varchar(3)     NOT NULL,
    amount_usd             numeric(30, 2) NOT NULL,
    action                 varchar(10)    NOT NULL,
    rule_set_version       integer        NOT NULL REFERENCES risk_rule_sets (version),
    step_up_confirmed      boolean        NOT NULL,
    first_payment          boolean        NOT NULL,
    payee_age_seconds      bigint         NOT NULL,
    created_at             timestamptz    NOT NULL,
    CONSTRAINT risk_decisions_action_chk CHECK (action IN ('ALLOW', 'WARN', 'STEP_UP', 'HOLD', 'BLOCK'))
);

CREATE INDEX risk_decisions_transfer_idx ON risk_decisions (transfer_id);
CREATE INDEX risk_decisions_source_idx ON risk_decisions (source_account_id, created_at DESC);

-- The rules that fired for a decision, in evaluation order.
CREATE TABLE risk_decision_hits (
    decision_id uuid         NOT NULL REFERENCES risk_decisions (id),
    position    integer      NOT NULL,
    rule_id     varchar(50)  NOT NULL,
    action      varchar(10)  NOT NULL,
    detail      varchar(300) NOT NULL,
    PRIMARY KEY (decision_id, position)
);

-- The audit trail is append-only: rows are never changed or deleted, not even by a bug.
--
-- [jooq ignore start]
CREATE FUNCTION forbid_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER risk_decisions_append_only
    BEFORE UPDATE OR DELETE ON risk_decisions
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TRIGGER risk_decision_hits_append_only
    BEFORE UPDATE OR DELETE ON risk_decision_hits
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TRIGGER risk_rule_sets_append_only
    BEFORE UPDATE OR DELETE ON risk_rule_sets
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TRIGGER risk_rules_append_only
    BEFORE UPDATE OR DELETE ON risk_rules
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TRIGGER risk_rule_params_append_only
    BEFORE UPDATE OR DELETE ON risk_rule_params
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
-- [jooq ignore stop]
