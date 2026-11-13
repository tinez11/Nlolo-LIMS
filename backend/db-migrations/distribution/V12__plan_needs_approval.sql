-- db-migrations/distribution/V12__plan_needs_approval.sql
-- A commission plan takes a second person (2026-11-12, finance finding 190: a rate change moves money to agents, and
-- one person could make it). A new plan -- or an agent's own rate -- is PROPOSED and earns nothing until someone with
-- commission.approve approves it (ACTIVE) or rejects it (REJECTED). The one who proposed it may approve it only holding
-- commission.approve-own (the user, 2026-11-12: "be done by one person on roles and permissions"), recorded as acting
-- alone. Plans already in place stay ACTIVE.
-- Re-runnable.
ALTER TABLE distribution.commission_plan DROP CONSTRAINT IF EXISTS commission_plan_status_check;
ALTER TABLE distribution.commission_plan
    ADD CONSTRAINT commission_plan_status_check CHECK (status IN ('PROPOSED','ACTIVE','RETIRED','REJECTED'));
ALTER TABLE distribution.commission_plan ADD COLUMN IF NOT EXISTS proposed_for_agent_id UUID;
ALTER TABLE distribution.commission_plan ADD COLUMN IF NOT EXISTS decided_by VARCHAR(100);
ALTER TABLE distribution.commission_plan ADD COLUMN IF NOT EXISTS decided_at TIMESTAMPTZ;
ALTER TABLE distribution.commission_plan ADD COLUMN IF NOT EXISTS decision_reason VARCHAR(500);
ALTER TABLE distribution.commission_plan ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
