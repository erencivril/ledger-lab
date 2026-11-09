-- Core ledger schema.
--
-- Money columns are numeric(30,3): large enough for any amount the API
-- accepts and wide enough for the currency with the most minor units (KWD, 3).
-- The application rounds to each currency's own scale before writing.

CREATE TABLE accounts (
    id         uuid PRIMARY KEY,
    owner      varchar(100) NOT NULL,
    currency   varchar(3)   NOT NULL,
    kind       varchar(10)  NOT NULL DEFAULT 'CUSTOMER',
    -- Maintained for CUSTOMER accounts only. A SYSTEM (treasury) account has
    -- no stored balance: it would be a hot row touched by every transfer, so
    -- its balance is the sum of its journal entries.
    balance    numeric(30, 3) NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT accounts_kind_chk CHECK (kind IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT accounts_balance_chk CHECK (kind = 'SYSTEM' OR balance >= 0)
);

CREATE UNIQUE INDEX accounts_one_system_per_currency
    ON accounts (currency) WHERE kind = 'SYSTEM';

CREATE TABLE fx_rates (
    from_currency varchar(3)     NOT NULL,
    to_currency   varchar(3)     NOT NULL,
    rate          numeric(20, 8) NOT NULL,
    PRIMARY KEY (from_currency, to_currency),
    CONSTRAINT fx_rates_rate_chk CHECK (rate > 0),
    CONSTRAINT fx_rates_pair_chk CHECK (from_currency <> to_currency)
);

CREATE TABLE transfers (
    id                     uuid PRIMARY KEY,
    kind                   varchar(20)    NOT NULL,
    source_account_id      uuid           NOT NULL REFERENCES accounts (id),
    destination_account_id uuid           NOT NULL REFERENCES accounts (id),
    source_amount          numeric(30, 3) NOT NULL,
    source_currency        varchar(3)     NOT NULL,
    destination_amount     numeric(30, 3) NOT NULL,
    destination_currency   varchar(3)     NOT NULL,
    -- The rate that was applied. Null when both accounts share a currency.
    fx_rate                numeric(20, 8),
    created_at             timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT transfers_kind_chk CHECK (kind IN ('TRANSFER', 'OPENING_DEPOSIT')),
    CONSTRAINT transfers_amounts_chk CHECK (source_amount > 0 AND destination_amount > 0),
    CONSTRAINT transfers_distinct_chk CHECK (source_account_id <> destination_account_id)
);

-- Append-only. One row per account touched by a transfer.
CREATE TABLE journal_entries (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id   uuid           NOT NULL REFERENCES transfers (id),
    account_id    uuid           NOT NULL REFERENCES accounts (id),
    currency      varchar(3)     NOT NULL,
    -- Signed: negative is a debit, positive is a credit.
    amount        numeric(30, 3) NOT NULL,
    -- Customer accounts only; null for treasury entries.
    balance_after numeric(30, 3),
    created_at    timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT journal_entries_amount_chk CHECK (amount <> 0)
);

CREATE INDEX journal_entries_account_idx ON journal_entries (account_id, id DESC);
CREATE INDEX journal_entries_transfer_idx ON journal_entries (transfer_id);

-- The source of truth for idempotency. The primary key is the unique
-- constraint that makes two concurrent requests with the same key race safely.
CREATE TABLE idempotency_keys (
    idempotency_key     varchar(255) PRIMARY KEY,
    request_fingerprint varchar(64)  NOT NULL,
    transfer_id         uuid REFERENCES transfers (id),
    created_at          timestamptz  NOT NULL DEFAULT now()
);
