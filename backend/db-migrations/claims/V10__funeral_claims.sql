-- db-migrations/claims/V10__funeral_claims.sql
-- Family funeral cover: a claim on a funeral plan names the covered life who died, and whether the death
-- was accidental (an accident may waive the waiting period). Its own 1:1 table rather than columns on
-- claims.claim -- a mapped column there would make every class that settles a claim need this migration --
-- read only after the policy says FUNERAL.
CREATE TABLE claims.funeral_claim (
    claim_id               UUID PRIMARY KEY REFERENCES claims.claim(claim_id),
    tenant_id              UUID NOT NULL,
    covered_life_id        UUID NOT NULL,
    accidental             BOOLEAN NOT NULL DEFAULT false,
    accidental_recorded_by VARCHAR(100),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_funeral_claim_life ON claims.funeral_claim (tenant_id, covered_life_id);

-- A death inside a life's waiting period is declined for it (plan R7), through the same gate that
-- refuses a reason whose window had already closed.
ALTER TABLE claims.claim DROP CONSTRAINT chk_claim_decline_reason_known;
ALTER TABLE claims.claim ADD CONSTRAINT chk_claim_decline_reason_known CHECK (
    decline_reason IS NULL OR decline_reason IN
        ('SUICIDE_WITHIN_EXCLUSION', 'PRE_EXISTING_WITHIN_EXCLUSION', 'WITHIN_WAITING_PERIOD'));

ALTER TABLE claims.funeral_claim ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_claim_tenant_isolation ON claims.funeral_claim
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON claims.funeral_claim TO app_role;
