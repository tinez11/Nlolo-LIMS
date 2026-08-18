-- Module: reinsurance V2 -- M8 Task 1.
--
-- V1 shipped the same defect set this project has now caught in five consecutive
-- modules (claims, payment, billing, policyloan, distribution): RLS enabled on
-- only ONE of its three tables, ZERO grants to app_role anywhere in the file, no
-- optimistic locking, no money guards, and no uniqueness on either financial
-- record -- so a redelivered event would silently duplicate a cession.
-- =============================================================================
-- 1. Grants. V1 grants app_role nothing at all, so the application's real
--    runtime role could not read or write this schema. Every other module's V1
--    had this same hole; AppRolePrivilegesIntegrationTest (Task 9) is the guard.
-- =============================================================================
GRANT USAGE ON SCHEMA reinsurance TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reinsurance TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA reinsurance GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- =============================================================================
-- 2. RLS on the two tables V1 left unprotected. V1 enabled it on
--    reinsurance_treaty only -- cession and claim_recovery both carry tenant_id
--    NOT NULL and had no policy at all, so app_role could read every tenant's
--    ceded amounts and recoveries.
-- =============================================================================
ALTER TABLE reinsurance.cession ENABLE ROW LEVEL SECURITY;
CREATE POLICY cession_tenant_isolation ON reinsurance.cession
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE reinsurance.claim_recovery ENABLE ROW LEVEL SECURITY;
CREATE POLICY claim_recovery_tenant_isolation ON reinsurance.claim_recovery
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 3. Missing tenant indexes (docs/06-database-schema.md:25 requires tenant_id
--    indexed on every tenant-scoped table; V1 indexed only the treaty's).
-- =============================================================================
CREATE INDEX idx_cession_tenant ON reinsurance.cession (tenant_id);
CREATE INDEX idx_claim_recovery_tenant ON reinsurance.claim_recovery (tenant_id);

-- =============================================================================
-- 4. Optimistic locking on the two aggregate roots with real concurrent-write
--    exposure. `cession` is deliberately excluded: it is written exactly once
--    per (policy, treaty) and never mutated, so a version column would be dead
--    weight. docs/06's own list is amended to match in Task 9.
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reinsurance.claim_recovery ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- =============================================================================
-- 5. Audit columns (docs/06's convention: created_by/updated_at/updated_by on
--    every mutable table). V1 has created_at only.
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN created_by VARCHAR(100);
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN updated_by VARCHAR(100);
ALTER TABLE reinsurance.claim_recovery ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE reinsurance.claim_recovery ADD COLUMN updated_by VARCHAR(100);

-- =============================================================================
-- 6. The reinsurer. V1 models a treaty with NO counterparty whatsoever -- no
--    name, no reference -- which makes a treaty row meaningless as a contract.
--    A plain name, not a party FK: `party` models no reinsurer party type, and
--    `reinsurance` cannot call `party` synchronously (Global Constraints).
--    200 chars comfortably fits a legal entity name; VARCHAR(100) is this
--    platform's convention for person-scale names and would be tight here.
--
--    NOT NULL with a DEFAULT then dropped: no rows exist in practice (nothing
--    has ever written this table), but a bare NOT NULL add would fail if one
--    did, and silently succeeding on an empty table is exactly the kind of
--    thing that breaks on the first real deployment that has data.
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN reinsurer_name VARCHAR(200) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE reinsurance.reinsurance_treaty ALTER COLUMN reinsurer_name DROP DEFAULT;

-- =============================================================================
-- 7. Treaty status. Selection (Task 4) must consider only ACTIVE treaties, and
--    V1 has no way to retire one without deleting the row -- which would orphan
--    every cession that referenced it via FK.
--
--    VARCHAR(20) against a longest value of 'EXPIRED' (7). Deliberately checked
--    rather than assumed: M7 shipped a CHECK admitting a 16-character value
--    into a VARCHAR(15) column, making a whole payout path unwritable while
--    every test stayed green. V1's own treaty_type VARCHAR(15) is also verified
--    here -- longest value 'QUOTA_SHARE' is 11 -- and needs no widening.
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','EXPIRED'));

-- =============================================================================
-- 8. Money guards. V1 has none, so a treaty could carry a negative retention
--    and a cession a zero or negative ceded amount.
--
--    Note the asymmetry, which is deliberate: retention_limit_amount >= 0
--    (a zero retention means "cede everything", a legitimate quota-share
--    arrangement), but ceded and recoverable amounts must be strictly > 0
--    (a zero-value financial record is not a record -- the calculators return
--    empty rather than a zero row, see Tasks 4 and 5).
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty
    ADD CONSTRAINT treaty_retention_non_negative CHECK (retention_limit_amount >= 0);
ALTER TABLE reinsurance.cession
    ADD CONSTRAINT cession_ceded_amount_positive CHECK (ceded_amount > 0);
ALTER TABLE reinsurance.claim_recovery
    ADD CONSTRAINT recovery_amount_positive CHECK (recoverable_amount > 0);

-- cession_percent is meaningful only for QUOTA_SHARE, and must be a real
-- percentage when present. NUMERIC(5,2) holds 100.00 fine (max 999.99).
ALTER TABLE reinsurance.reinsurance_treaty
    ADD CONSTRAINT treaty_cession_percent_range
    CHECK (cession_percent IS NULL OR (cession_percent > 0 AND cession_percent <= 100));

-- QUOTA_SHARE cannot compute a cession without a percent; the other two types
-- must not carry one (SURPLUS cedes by retention, XOL does not cede at issuance).
ALTER TABLE reinsurance.reinsurance_treaty
    ADD CONSTRAINT treaty_cession_percent_required_for_quota_share
    CHECK ((treaty_type = 'QUOTA_SHARE' AND cession_percent IS NOT NULL)
        OR (treaty_type <> 'QUOTA_SHARE' AND cession_percent IS NULL));

-- =============================================================================
-- 9. Ceded premium (spec decision, added at review). A quota-share treaty cedes
--    PREMIUM as well as risk, and finaccounting (M9) needs it for IFRS 17
--    ceded-business entries. policy.PolicyIssued already carries `premium`, so
--    the input exists today and retrofitting later would mean re-deriving every
--    cession already written.
--
--    Nullable and PAIRED: ceded risk is the primary fact, and amount+currency
--    travel together or not at all -- the same paired-nullability CHECK
--    distribution.commission_rule uses for its flat amount.
-- =============================================================================
ALTER TABLE reinsurance.cession ADD COLUMN ceded_premium_amount NUMERIC(19,2);
ALTER TABLE reinsurance.cession ADD COLUMN ceded_premium_currency CHAR(3);
ALTER TABLE reinsurance.cession
    ADD CONSTRAINT cession_ceded_premium_positive
    CHECK (ceded_premium_amount IS NULL OR ceded_premium_amount > 0);
ALTER TABLE reinsurance.cession
    ADD CONSTRAINT cession_ceded_premium_paired
    CHECK ((ceded_premium_amount IS NULL) = (ceded_premium_currency IS NULL));

-- =============================================================================
-- 10. Idempotency. V1 has NO uniqueness on either financial table, so a
--     redelivered PolicyIssued or ClaimSettled -- at-least-once delivery is the
--     platform's stated contract -- would write a SECOND cession or recovery for
--     the same facts, double-counting ceded risk and recoverables. These indexes
--     are the real backstop; the application-layer checks in Tasks 4 and 5 are a
--     convenience early-return, not the source of truth.
-- =============================================================================
CREATE UNIQUE INDEX ux_cession_once ON reinsurance.cession (tenant_id, policy_number, treaty_id);
CREATE UNIQUE INDEX ux_recovery_once ON reinsurance.claim_recovery (tenant_id, claim_id, treaty_id);

-- =============================================================================
-- 11. policy_projection -- reinsurance's OWN state, not a cache of policy's.
--
--     Why it must exist: `reinsurance` may call only `refdata` synchronously
--     (docs/02:175), so PolicyApi is unreachable. Cession needs the policy's sum
--     assured AND premium; recovery needs to resolve a claim's policy to its
--     cession. Built solely from policy.PolicyIssued.
--
--     Deliberately NOT reconciled against `policy` -- that would need the
--     forbidden dependency. A policy issued before M8 has no row here, so it
--     cedes nothing and recovers nothing; that is correct, since those policies
--     predate reinsurance tracking entirely. Log and move on, never throw.
-- =============================================================================
CREATE TABLE reinsurance.policy_projection (
    policy_number       VARCHAR(20) NOT NULL,
    tenant_id            UUID NOT NULL,
    product_id            UUID NOT NULL,
    sum_assured_amount     NUMERIC(19,2) NOT NULL CHECK (sum_assured_amount > 0),
    sum_assured_currency    CHAR(3) NOT NULL,
    premium_amount           NUMERIC(19,2) NOT NULL CHECK (premium_amount > 0),
    premium_currency          CHAR(3) NOT NULL,
    issue_date                 DATE NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number)
);
CREATE INDEX idx_reinsurance_projection_tenant ON reinsurance.policy_projection (tenant_id);

ALTER TABLE reinsurance.policy_projection ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_projection_tenant_isolation ON reinsurance.policy_projection
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON reinsurance.policy_projection TO app_role;
