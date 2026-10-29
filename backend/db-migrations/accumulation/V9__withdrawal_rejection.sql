-- db-migrations/accumulation/V9__withdrawal_rejection.sql
-- A withdrawal finance refuses (2026-10-29, the user): until now a request nobody wanted could only be approved or left
-- waiting for ever. REJECTED says who refused it, when and why; nothing is posted -- the money never left the account,
-- because it leaves only at approval.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE accumulation.withdrawal_request DROP CONSTRAINT IF EXISTS withdrawal_request_status_check;
ALTER TABLE accumulation.withdrawal_request
    ADD CONSTRAINT withdrawal_request_status_check
    CHECK (status IN ('REQUESTED', 'APPROVED', 'PAID', 'FAILED', 'REJECTED'));

ALTER TABLE accumulation.withdrawal_request ADD COLUMN IF NOT EXISTS rejected_by VARCHAR(100);
ALTER TABLE accumulation.withdrawal_request ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ;
ALTER TABLE accumulation.withdrawal_request ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(500);

-- A refusal names its reason and is not the requester's own: the same two people as an approval.
ALTER TABLE accumulation.withdrawal_request DROP CONSTRAINT IF EXISTS chk_withdrawal_rejection;
ALTER TABLE accumulation.withdrawal_request
    ADD CONSTRAINT chk_withdrawal_rejection CHECK (
        (status <> 'REJECTED' AND rejected_by IS NULL)
        OR (status = 'REJECTED' AND rejected_by IS NOT NULL AND rejection_reason IS NOT NULL
            AND rejected_by <> requested_by));
