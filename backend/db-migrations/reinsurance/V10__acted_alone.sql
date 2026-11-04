-- db-migrations/reinsurance/V10__acted_alone.sql
-- Acting alone (2026-11-04, the user: a company with one finance person must still finish its work). A person given
-- reinsurance.approve-own may approve or reject a statement they prepared; the row says so in acted_alone, and the
-- two-person rule holds for everyone else.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS reinsurance.statement ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS reinsurance.statement DROP CONSTRAINT IF EXISTS statement_decider_not_preparer;
ALTER TABLE IF EXISTS reinsurance.statement
    ADD CONSTRAINT statement_decider_not_preparer CHECK (decided_by IS NULL OR decided_by <> preparer OR acted_alone);
