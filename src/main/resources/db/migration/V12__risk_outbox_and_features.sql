-- The database writes completion events in the money transaction. Pollers acknowledge them only after
-- updating the feature tables, in another transaction. No broker and no dual-write gap.
CREATE TABLE risk_outbox (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id uuid NOT NULL UNIQUE REFERENCES transfers(id),
    sender_id uuid NOT NULL REFERENCES accounts(id),
    payee_id uuid NOT NULL REFERENCES accounts(id),
    amount numeric(30,3) NOT NULL,
    currency varchar(3) NOT NULL,
    amount_usd numeric(30,2) NOT NULL,
    completed_at timestamptz NOT NULL,
    processed_at timestamptz
);
CREATE INDEX risk_outbox_pending_idx ON risk_outbox(id) WHERE processed_at IS NULL;
CREATE INDEX risk_outbox_sender_pending_idx ON risk_outbox(sender_id) WHERE processed_at IS NULL;

-- One row per consumed event is the deduplication key and a time-windowed payment history.
CREATE TABLE risk_features_payments (
    transfer_id uuid PRIMARY KEY REFERENCES transfers(id),
    sender_id uuid NOT NULL,
    payee_id uuid NOT NULL,
    amount numeric(30,3) NOT NULL,
    currency varchar(3) NOT NULL,
    amount_usd numeric(30,2) NOT NULL,
    completed_at timestamptz NOT NULL
);
CREATE INDEX risk_features_history_idx ON risk_features_payments(sender_id, completed_at);
CREATE TABLE risk_features_sender (
    sender_id uuid PRIMARY KEY REFERENCES accounts(id),
    payment_count bigint NOT NULL,
    total_usd numeric(38,2) NOT NULL,
    last_payment_at timestamptz NOT NULL
);
CREATE TABLE risk_features_payee (
    sender_id uuid NOT NULL REFERENCES accounts(id),
    payee_id uuid NOT NULL REFERENCES accounts(id),
    payment_count bigint NOT NULL,
    last_payment_at timestamptz NOT NULL,
    PRIMARY KEY(sender_id, payee_id)
);

-- [jooq ignore start]
CREATE FUNCTION emit_payment_completed() RETURNS trigger AS $$
BEGIN
    IF NEW.kind = 'TRANSFER' AND NEW.status = 'COMPLETED' THEN
        INSERT INTO risk_outbox(transfer_id, sender_id, payee_id, amount, currency, amount_usd, completed_at)
        VALUES(NEW.id, NEW.source_account_id, NEW.destination_account_id, NEW.source_amount, NEW.source_currency,
            round(NEW.source_amount * CASE WHEN NEW.source_currency = 'USD' THEN 1
                ELSE (SELECT rate FROM fx_rates WHERE from_currency = NEW.source_currency AND to_currency = 'USD') END, 2),
            coalesce(NEW.resolved_at, NEW.created_at))
        ON CONFLICT(transfer_id) DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER transfers_outbox AFTER INSERT OR UPDATE OF status ON transfers
    FOR EACH ROW EXECUTE FUNCTION emit_payment_completed();

-- Existing completed payments also need a projection when upgrading an existing database.
INSERT INTO risk_outbox(transfer_id, sender_id, payee_id, amount, currency, amount_usd, completed_at)
SELECT t.id, t.source_account_id, t.destination_account_id, t.source_amount, t.source_currency,
    round(t.source_amount * CASE WHEN t.source_currency = 'USD' THEN 1 ELSE f.rate END, 2),
    coalesce(t.resolved_at, t.created_at)
FROM transfers t LEFT JOIN fx_rates f ON f.from_currency = t.source_currency AND f.to_currency = 'USD'
WHERE t.kind = 'TRANSFER' AND t.status = 'COMPLETED';
-- [jooq ignore stop]
