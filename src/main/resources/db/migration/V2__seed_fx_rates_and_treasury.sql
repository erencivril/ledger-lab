-- Seed data: the fixed FX rate table and one treasury (system) account per currency.
--
-- The rates are illustrative and fixed on purpose. They are derived from one
-- set of USD base rates and rounded to 8 decimals, so a rate and its inverse
-- are not exact reciprocals. They are not market data.
--
-- [jooq ignore start]
INSERT INTO fx_rates (from_currency, to_currency, rate) VALUES
    ('USD', 'EUR', 0.92000000),
    ('USD', 'GBP', 0.79000000),
    ('USD', 'TRY', 41.50000000),
    ('USD', 'JPY', 151.20000000),
    ('USD', 'KWD', 0.30700000),
    ('EUR', 'USD', 1.08695652),
    ('EUR', 'GBP', 0.85869565),
    ('EUR', 'TRY', 45.10869565),
    ('EUR', 'JPY', 164.34782609),
    ('EUR', 'KWD', 0.33369565),
    ('GBP', 'USD', 1.26582278),
    ('GBP', 'EUR', 1.16455696),
    ('GBP', 'TRY', 52.53164557),
    ('GBP', 'JPY', 191.39240506),
    ('GBP', 'KWD', 0.38860759),
    ('TRY', 'USD', 0.02409639),
    ('TRY', 'EUR', 0.02216867),
    ('TRY', 'GBP', 0.01903614),
    ('TRY', 'JPY', 3.64337349),
    ('TRY', 'KWD', 0.00739759),
    ('JPY', 'USD', 0.00661376),
    ('JPY', 'EUR', 0.00608466),
    ('JPY', 'GBP', 0.00522487),
    ('JPY', 'TRY', 0.27447090),
    ('JPY', 'KWD', 0.00203042),
    ('KWD', 'USD', 3.25732899),
    ('KWD', 'EUR', 2.99674267),
    ('KWD', 'GBP', 2.57328990),
    ('KWD', 'TRY', 135.17915309),
    ('KWD', 'JPY', 492.50814332);

INSERT INTO accounts (id, owner, currency, kind) VALUES
    ('00000000-0000-0000-0000-000000000001', 'treasury', 'USD', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000002', 'treasury', 'EUR', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000003', 'treasury', 'GBP', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000004', 'treasury', 'TRY', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000005', 'treasury', 'JPY', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000006', 'treasury', 'KWD', 'SYSTEM');
-- [jooq ignore stop]
