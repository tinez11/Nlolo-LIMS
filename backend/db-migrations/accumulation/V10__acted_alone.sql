-- db-migrations/accumulation/V10__acted_alone.sql
-- Acting alone (2026-11-04, the user: a company with one finance person must still finish its work). A person given
-- accounts.approve-own or products.declare-approve-own may approve what they asked for themselves; the row says so in
-- acted_alone, and the two-person rule holds for everyone else.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS accumulation.withdrawal_request ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS accumulation.withdrawal_request DROP CONSTRAINT IF EXISTS withdrawal_two_person;
ALTER TABLE IF EXISTS accumulation.withdrawal_request
    ADD CONSTRAINT withdrawal_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by OR acted_alone);
ALTER TABLE IF EXISTS accumulation.withdrawal_request DROP CONSTRAINT IF EXISTS chk_withdrawal_rejection;
ALTER TABLE IF EXISTS accumulation.withdrawal_request
    ADD CONSTRAINT chk_withdrawal_rejection CHECK (
        (status <> 'REJECTED' AND rejected_by IS NULL)
        OR (status = 'REJECTED' AND rejected_by IS NOT NULL AND rejection_reason IS NOT NULL
            AND (rejected_by <> requested_by OR acted_alone)));

ALTER TABLE IF EXISTS accumulation.adjustment_request ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS accumulation.adjustment_request DROP CONSTRAINT IF EXISTS adjustment_two_person;
ALTER TABLE IF EXISTS accumulation.adjustment_request
    ADD CONSTRAINT adjustment_two_person CHECK (decided_by IS NULL OR decided_by <> proposed_by OR acted_alone);

-- The ledger line an approved adjustment posts carries the same mark: it is checked against its own creator.
ALTER TABLE IF EXISTS accumulation.ledger_entry ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS accumulation.ledger_entry DROP CONSTRAINT IF EXISTS ledger_entry_adjustment_approved;
ALTER TABLE IF EXISTS accumulation.ledger_entry
    ADD CONSTRAINT ledger_entry_adjustment_approved CHECK (
        entry_type <> 'ADJUSTMENT' OR (approved_by IS NOT NULL AND (approved_by <> created_by OR acted_alone)));

ALTER TABLE IF EXISTS accumulation.rate_declaration ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS accumulation.rate_declaration DROP CONSTRAINT IF EXISTS rate_declaration_two_person;
ALTER TABLE IF EXISTS accumulation.rate_declaration
    ADD CONSTRAINT rate_declaration_two_person CHECK (approved_by IS NULL OR approved_by <> proposed_by OR acted_alone);
