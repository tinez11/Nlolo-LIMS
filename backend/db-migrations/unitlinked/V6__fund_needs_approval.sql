-- 2026-11-12, finance finding 126: one person added a fund, with any management charge, live at once. A new fund is
-- PROPOSED and takes no money and no price until a second person approves it (OPEN) or rejects it (REJECTED). Its
-- proposer approves it alone only holding funds.approve-own, and the row says so.
ALTER TABLE unitlinked.fund DROP CONSTRAINT IF EXISTS fund_status_check;
ALTER TABLE unitlinked.fund ALTER COLUMN status SET DEFAULT 'PROPOSED';
ALTER TABLE unitlinked.fund ADD CONSTRAINT fund_status_check CHECK (status IN ('PROPOSED','OPEN','CLOSED','REJECTED'));
ALTER TABLE unitlinked.fund ADD COLUMN IF NOT EXISTS decided_by VARCHAR(100);
ALTER TABLE unitlinked.fund ADD COLUMN IF NOT EXISTS decided_at TIMESTAMPTZ;
ALTER TABLE unitlinked.fund ADD COLUMN IF NOT EXISTS decision_reason VARCHAR(500);
ALTER TABLE unitlinked.fund ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
