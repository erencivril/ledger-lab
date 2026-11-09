-- Holds: a transfer can wait for a decision before the destination is paid.
--
-- A held transfer takes the money out of the sender at once and parks it in a HOLD account, one per
-- currency. The HOLD account has no stored balance (like the treasury, its balance is the sum of its
-- journal entries). Release pays the destination from it; reject pays the sender back.

ALTER TABLE transfers ADD COLUMN status varchar(10) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE transfers ADD COLUMN resolved_at timestamptz;
ALTER TABLE transfers ADD CONSTRAINT transfers_status_chk CHECK (status IN ('COMPLETED', 'PENDING', 'REJECTED'));

ALTER TABLE accounts DROP CONSTRAINT accounts_kind_chk;
ALTER TABLE accounts ADD CONSTRAINT accounts_kind_chk CHECK (kind IN ('CUSTOMER', 'SYSTEM', 'HOLD'));

CREATE UNIQUE INDEX accounts_one_hold_per_currency
    ON accounts (currency) WHERE kind = 'HOLD';

-- [jooq ignore start]
INSERT INTO accounts (id, owner, currency, kind) VALUES
    ('00000000-0000-0000-0000-000000000101', 'hold', 'USD', 'HOLD'),
    ('00000000-0000-0000-0000-000000000102', 'hold', 'EUR', 'HOLD'),
    ('00000000-0000-0000-0000-000000000103', 'hold', 'GBP', 'HOLD'),
    ('00000000-0000-0000-0000-000000000104', 'hold', 'TRY', 'HOLD'),
    ('00000000-0000-0000-0000-000000000105', 'hold', 'JPY', 'HOLD'),
    ('00000000-0000-0000-0000-000000000106', 'hold', 'KWD', 'HOLD');
-- [jooq ignore stop]
