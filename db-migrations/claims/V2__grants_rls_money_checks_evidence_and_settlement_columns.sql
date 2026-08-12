-- db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql
-- Completes claims/V1, which shipped with zero GRANTs and RLS on 1 of its 3 tables -- the exact
-- bug class billing/V2:1-3 documents for billing/V1, and that M1/M2/M3 each fixed for every
-- other schema. Migrations run as the Postgres superuser (scripts/migrate.sh), which owns the
-- schema, so this gap was invisible to any test whose DataSource connected as that same
-- superuser. app_role is the app's real runtime identity and had no access whatsoever.

-- =============================================================================
-- 1. GRANTs. Without these the module is unreachable at runtime.
-- =============================================================================
GRANT USAGE ON SCHEMA claims TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA claims TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA claims GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- =============================================================================
-- 2. RLS completion. V1 covered claims.claim only; both child tables carry
--    tenant_id NOT NULL and had no policy at all, so app_role could read every
--    tenant's assessments and settlement decisions.
-- =============================================================================
ALTER TABLE claims.claim_assessment ENABLE ROW LEVEL SECURITY;
CREATE POLICY claim_assessment_tenant_isolation ON claims.claim_assessment
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE claims.settlement_decision ENABLE ROW LEVEL SECURITY;
CREATE POLICY settlement_decision_tenant_isolation ON claims.settlement_decision
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 3. Money guards. Every other module got these (policy/V2, policyloan/V3,
--    billing/V2:38-41); claims/V1 has none. A non-positive approved amount is a
--    data-entry error, never a legitimate settlement.
-- =============================================================================
ALTER TABLE claims.claim
    ADD CONSTRAINT claim_approved_amount_positive
    CHECK (approved_amount IS NULL OR approved_amount > 0);

ALTER TABLE claims.claim_assessment
    ADD CONSTRAINT claim_assessment_recommended_amount_positive
    CHECK (recommended_amount IS NULL OR recommended_amount > 0);

ALTER TABLE claims.settlement_decision
    ADD CONSTRAINT settlement_decision_approved_amount_positive
    CHECK (approved_amount IS NULL OR approved_amount > 0);

-- =============================================================================
-- 4. Evidence linkage. claims/V1 has NO table linking a claim to the document
--    refs that evidence it, despite "document evidence upload/retrieval tested
--    against MinIO" being M6 acceptance criteria. document_ref is an opaque
--    string returned by DocumentApi.upload -- no FK, per docs/06:29.
-- =============================================================================
CREATE TABLE claims.claim_evidence (
    claim_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    claim_id          UUID NOT NULL REFERENCES claims.claim(claim_id),
    document_ref      VARCHAR(100) NOT NULL,   -- opaque ref into document, never an FK
    description       VARCHAR(500),
    uploaded_by       VARCHAR(100) NOT NULL,
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT claim_evidence_unique_ref_per_claim UNIQUE (claim_id, document_ref)
);
CREATE INDEX idx_claim_evidence_claim ON claims.claim_evidence (claim_id);
CREATE INDEX idx_claim_evidence_tenant ON claims.claim_evidence (tenant_id);

ALTER TABLE claims.claim_evidence ENABLE ROW LEVEL SECURITY;
CREATE POLICY claim_evidence_tenant_isolation ON claims.claim_evidence
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON claims.claim_evidence TO app_role;

-- =============================================================================
-- 5. Settlement columns.
--    payee_ref: the mobile-money destination, supplied by the CLAIMS_MANAGER at
--      decision time (for a DEATH claim the payee is a beneficiary, not the
--      claimant, and PartyView exposes no MSISDN, so it cannot be derived).
--    settlement_idempotency_key: the client-supplied key forwarded to payment.
--      Persisted so a retry after DisbursementFailed can deliberately use a NEW
--      key while the original remains auditable.
--    settlement_failure_reason: on payment.DisbursementFailed the claim returns
--      to APPROVED (retryable) and this preserves why -- the "staff retry
--      worklist" of docs/02:125.
-- =============================================================================
ALTER TABLE claims.settlement_decision ADD COLUMN payee_ref VARCHAR(100);
ALTER TABLE claims.claim ADD COLUMN settlement_idempotency_key VARCHAR(100);
ALTER TABLE claims.claim ADD COLUMN settlement_failure_reason VARCHAR(500);

-- Idempotency keys are tenant-scoped, matching payment/V2's registry fix (a
-- global-unique key would let one tenant's key collide with another's).
CREATE UNIQUE INDEX idx_claim_settlement_key
    ON claims.claim (tenant_id, settlement_idempotency_key)
    WHERE settlement_idempotency_key IS NOT NULL;
