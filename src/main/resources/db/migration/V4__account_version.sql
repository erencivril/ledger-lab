-- A counter that goes up by one every time the balance of an account is written.
--
-- The optimistic strategy uses it as a compare-and-set: "UPDATE ... WHERE version = <the one I read>"
-- changes no row if somebody else wrote in between. The other strategies bump it too, so the column
-- always means "how many times this balance has changed" whichever strategy is configured.
ALTER TABLE accounts ADD COLUMN version bigint NOT NULL DEFAULT 0;
