-- 2026-11-12, finance finding 170: one person created a treaty, which moves premium to an outside company every month,
-- with no approval. A treaty made through the platform is PROPOSED and cedes nothing until a second person approves it
-- (ACTIVE) or rejects it (REJECTED). Its proposer approves it alone only holding reinsurance.approve-own, and the row
-- says so. Treaties already in force stay ACTIVE.
ALTER TABLE reinsurance.reinsurance_treaty DROP CONSTRAINT IF EXISTS reinsurance_treaty_status_check;
ALTER TABLE reinsurance.reinsurance_treaty ADD CONSTRAINT reinsurance_treaty_status_check
    CHECK (status IN ('PROPOSED','ACTIVE','EXPIRED','REJECTED'));
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN IF NOT EXISTS decided_by VARCHAR(100);
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN IF NOT EXISTS decided_at TIMESTAMPTZ;
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN IF NOT EXISTS decision_reason VARCHAR(500);
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
