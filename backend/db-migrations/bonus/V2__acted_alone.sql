-- db-migrations/bonus/V2__acted_alone.sql
-- Acting alone (2026-11-04, the user: a company with one actuary or finance person must still finish its work). A
-- person given products.declare-approve-own may approve a bonus declaration they proposed; the row says so in
-- acted_alone, and the two-person rule holds for everyone else.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS bonus.declaration ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS bonus.declaration DROP CONSTRAINT IF EXISTS declaration_two_person;
ALTER TABLE IF EXISTS bonus.declaration
    ADD CONSTRAINT declaration_two_person CHECK (approved_by IS NULL OR approved_by <> proposed_by OR acted_alone);
