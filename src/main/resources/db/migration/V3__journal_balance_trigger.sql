-- Safety net: the database refuses to commit a transfer whose journal does not
-- sum to zero per currency, even if the application has a bug.
--
-- The trigger is a constraint trigger that is deferred to commit, so all the
-- entries of one transfer can be inserted first and are checked together.
--
-- [jooq ignore start]
CREATE FUNCTION check_journal_balanced() RETURNS trigger AS $$
DECLARE
    unbalanced_currency varchar(3);
BEGIN
    SELECT currency INTO unbalanced_currency
    FROM journal_entries
    WHERE transfer_id = NEW.transfer_id
    GROUP BY currency
    HAVING SUM(amount) <> 0
    LIMIT 1;

    IF unbalanced_currency IS NOT NULL THEN
        RAISE EXCEPTION 'journal for transfer % does not balance in %', NEW.transfer_id, unbalanced_currency
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();
-- [jooq ignore stop]
