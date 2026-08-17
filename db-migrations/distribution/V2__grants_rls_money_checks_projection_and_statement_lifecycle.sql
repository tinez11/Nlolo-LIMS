-- db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql
-- Completes distribution/V1, which shipped with zero GRANTs and RLS on 1 of its 4 tables -- the
-- same bug class billing/V2, payment/V2 and claims/V2 each document. Migrations run as the
-- Postgres superuser (scripts/migrate.sh), which owns every table, so the gap is invisible to any
-- test whose DataSource connects as that same superuser. app_role is the app's real runtime
-- identity and currently has no access whatsoever.

-- =============================================================================
-- 1. GRANTs. Without these the module is unreachable at runtime.
-- =============================================================================
GRANT USAGE ON SCHEMA distribution TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA distribution TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA distribution GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- =============================================================================
-- 2. RLS completion. V1 covered agent_profile only; the other three all carry
--    tenant_id NOT NULL and had no policy at all, so app_role could read every
--    tenant's commission plans, rules and statements.
-- =============================================================================
ALTER TABLE distribution.commission_plan ENABLE ROW LEVEL SECURITY;
CREATE POLICY commission_plan_tenant_isolation ON distribution.commission_plan
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE distribution.commission_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY commission_rule_tenant_isolation ON distribution.commission_rule
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE distribution.commission_statement ENABLE ROW LEVEL SECURITY;
CREATE POLICY commission_statement_tenant_isolation ON distribution.commission_statement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 3. Missing tenant index (docs/06-database-schema.md:40 requires tenant_id
--    indexed on every tenant-scoped table; V1 gave commission_rule none).
-- =============================================================================
CREATE INDEX idx_commission_rule_tenant ON distribution.commission_rule (tenant_id);

-- =============================================================================
-- 4. Optimistic locking on the two aggregate roots Di1 promoted. V1 has version
--    only on agent_profile. docs/06:27's list must be amended to match (Task 10).
-- =============================================================================
ALTER TABLE distribution.commission_plan ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE distribution.commission_statement ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Audit columns, per docs/06:28 ("on every mutable table"). V1 gave these three none.
ALTER TABLE distribution.commission_plan ADD COLUMN created_by VARCHAR(100);
ALTER TABLE distribution.commission_plan ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE distribution.commission_plan ADD COLUMN updated_by VARCHAR(100);
ALTER TABLE distribution.commission_statement ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE distribution.commission_statement ADD COLUMN updated_by VARCHAR(100);

-- =============================================================================
-- 5. Money guards. V1 has NONE.
--
--    Note the deliberate asymmetry with every other schema on this platform: an
--    accrual amount is CHECK (<> 0) rather than (> 0), and commission_statement
--    .total_amount gets NO positivity constraint at all -- because a clawback
--    (M7 user decision 2) is a genuinely negative accrual, and a period whose
--    clawbacks exceed its accruals has a legitimately negative total. A (> 0)
--    guard here would reject correct data. Stated explicitly so a future
--    "consistency" fix does not add one.
-- =============================================================================
ALTER TABLE distribution.commission_rule
    ADD CONSTRAINT commission_rule_rate_positive
    CHECK (rate IS NULL OR rate > 0);
ALTER TABLE distribution.commission_rule
    ADD CONSTRAINT commission_rule_flat_amount_positive
    CHECK (flat_amount IS NULL OR flat_amount > 0);

-- V1 leaves both nullable with no XOR, so a rule with NEITHER a rate nor a flat
-- amount is insertable and would silently accrue nothing. Exactly one must be set.
ALTER TABLE distribution.commission_rule
    ADD CONSTRAINT commission_rule_rate_xor_flat
    CHECK ((rate IS NOT NULL AND flat_amount IS NULL)
        OR (rate IS NULL AND flat_amount IS NOT NULL));

-- flat_amount and flat_currency travel together or not at all.
ALTER TABLE distribution.commission_rule
    ADD CONSTRAINT commission_rule_flat_currency_paired
    CHECK ((flat_amount IS NULL) = (flat_currency IS NULL));

-- =============================================================================
-- 6. Statement lifecycle. V1's CHECK is ('PENDING','PAID') -- two states, which
--    cannot express the request/confirm pattern this platform mandates for every
--    money movement: after publishing CommissionPayoutRequested the statement is
--    neither PENDING nor PAID, and a DisbursementFailed has nowhere to land.
--    Direct analogue of claims' markSettlementRequested/markSettlementFailed.
-- =============================================================================
-- Widen BEFORE re-pointing the CHECK. V1 sized this column VARCHAR(15) for its own
-- two-state vocabulary ('PENDING','PAID'), and 'PAYOUT_REQUESTED' is 16 characters.
-- Without this the CHECK would happily ADMIT a value the column type cannot physically
-- store, so every payout request would die at the INSERT with "value too long for type
-- character varying(15)" -- a constraint and a type disagreeing about the same column.
-- Caught in Task 8 by the first test that actually persisted a PAYOUT_REQUESTED row;
-- Task 5 shipped requestStatementPayout with no test that reached the database, which
-- is why the suite stayed green over a payout path that could never have worked.
-- 20 leaves headroom over the longest current value without inviting a novel.
ALTER TABLE distribution.commission_statement
    ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE distribution.commission_statement
    DROP CONSTRAINT commission_statement_status_check;
ALTER TABLE distribution.commission_statement
    ADD CONSTRAINT commission_statement_status_check CHECK (status IN
        ('OPEN','CLOSED','PAYOUT_REQUESTED','PAID','PAYOUT_FAILED'));

-- V1 defaults status to 'PENDING', which is no longer an allowed value. Re-point
-- the default to OPEN and migrate any existing row (there are none in practice --
-- no code has ever written this table -- but a DEFAULT that violates its own
-- CHECK is a trap for the next writer).
UPDATE distribution.commission_statement SET status = 'OPEN' WHERE status = 'PENDING';
ALTER TABLE distribution.commission_statement ALTER COLUMN status SET DEFAULT 'OPEN';

-- The payout columns, mirroring claims/V2's precedent exactly: payeeRef cannot be
-- derived (party.PartyView exposes no MSISDN), the idempotency key is persisted so
-- a retry after PAYOUT_FAILED can deliberately use a NEW key while the original
-- stays auditable, and the failure reason lands somewhere visible.
ALTER TABLE distribution.commission_statement ADD COLUMN payee_ref VARCHAR(100);
ALTER TABLE distribution.commission_statement ADD COLUMN payout_idempotency_key VARCHAR(100);
ALTER TABLE distribution.commission_statement ADD COLUMN payout_failure_reason VARCHAR(500);
ALTER TABLE distribution.commission_statement ADD COLUMN closed_at TIMESTAMPTZ;
ALTER TABLE distribution.commission_statement ADD COLUMN paid_at TIMESTAMPTZ;

-- Tenant-scoped, matching payment/V2's registry fix: a globally-unique key would
-- let one tenant's key collide with another's.
CREATE UNIQUE INDEX idx_commission_statement_payout_key
    ON distribution.commission_statement (tenant_id, payout_idempotency_key)
    WHERE payout_idempotency_key IS NOT NULL;

-- A statement is identified by (tenant, agent, period, currency). One currency per
-- statement, so an agent selling in two currencies gets two statements for the
-- period -- the minimal honest handling of a case no doc defines.
CREATE UNIQUE INDEX ux_commission_statement_identity
    ON distribution.commission_statement (tenant_id, agent_id, period, total_currency);

-- =============================================================================
-- 7. commission_accrual -- the per-event line items a statement totals.
--    V1 has no such table: commission_statement.total_amount is a bare number
--    with nothing behind it, so nothing could explain or audit a total, and
--    clawback would have nothing to reverse.
-- =============================================================================
CREATE TABLE distribution.commission_accrual (
    accrual_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    agent_id         UUID NOT NULL REFERENCES distribution.agent_profile(agent_id),
    statement_id     UUID REFERENCES distribution.commission_statement(statement_id),
    policy_number    VARCHAR(20) NOT NULL,   -- opaque ref into policy, never an FK
    tier_type        VARCHAR(20) NOT NULL CHECK (tier_type IN
        ('FIRST_YEAR','RENEWAL','OVERRIDE','SUPERVISOR_OVERRIDE','THRESHOLD_BONUS')),
    amount           NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    currency         CHAR(3) NOT NULL,
    period           VARCHAR(7) NOT NULL,    -- 'YYYY-MM', the period this accrual falls in
    -- The event that caused this accrual, for idempotent replay. For an issuance
    -- accrual this is the policy_number; for a renewal it is the invoice id; for a
    -- clawback it is the reversed accrual's own id.
    source_ref       VARCHAR(100) NOT NULL,
    reverses_accrual_id UUID REFERENCES distribution.commission_accrual(accrual_id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       VARCHAR(100)
);
CREATE INDEX idx_commission_accrual_tenant ON distribution.commission_accrual (tenant_id);
CREATE INDEX idx_commission_accrual_agent_period ON distribution.commission_accrual (agent_id, period);
CREATE INDEX idx_commission_accrual_statement ON distribution.commission_accrual (statement_id);
CREATE INDEX idx_commission_accrual_policy ON distribution.commission_accrual (policy_number);

-- Idempotent replay: the same (agent, tier, source_ref) can accrue only once, so a
-- redelivered PolicyIssued or PremiumCollected cannot double-pay. A clawback row is
-- excluded because it legitimately shares source_ref semantics with its target.
CREATE UNIQUE INDEX ux_commission_accrual_once
    ON distribution.commission_accrual (tenant_id, agent_id, tier_type, source_ref)
    WHERE reverses_accrual_id IS NULL;

-- A given accrual may be reversed at most once.
CREATE UNIQUE INDEX ux_commission_accrual_single_reversal
    ON distribution.commission_accrual (reverses_accrual_id)
    WHERE reverses_accrual_id IS NOT NULL;

ALTER TABLE distribution.commission_accrual ENABLE ROW LEVEL SECURITY;
CREATE POLICY commission_accrual_tenant_isolation ON distribution.commission_accrual
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON distribution.commission_accrual TO app_role;

-- =============================================================================
-- 8. policy_projection -- distribution's OWN state, not a cache of policy's.
--
--    Why this table has to exist: policy.PolicyLapsed carries only policyNumber
--    and lapsedAt (no agentOfRecordId, no productId), and `policy` is NOT in
--    distribution's allowedDependencies, so PolicyApi.getPolicy is unreachable
--    from here. Without a local projection, distribution cannot answer "whose
--    commission do I claw back?" at lapse time, nor "which agent and plan?" when
--    a renewal premium is collected. Built solely from policy.PolicyIssued.
--
--    Deliberately NOT reconciled against policy -- that would need the forbidden
--    dependency. A policy issued before M7 has no row here, so it accrues no
--    commission and is never clawed back; that is correct, since those policies
--    predate commission tracking entirely. Log and move on, never throw.
-- =============================================================================
CREATE TABLE distribution.policy_projection (
    policy_number      VARCHAR(20) NOT NULL,
    tenant_id          UUID NOT NULL,
    agent_id           UUID,   -- resolved from agentOfRecordId; NULL when a policy was sold direct
    product_id         UUID NOT NULL,
    premium_amount     NUMERIC(19,2) NOT NULL CHECK (premium_amount > 0),
    premium_currency   CHAR(3) NOT NULL,
    issue_date         DATE NOT NULL,
    -- WHICH invoice was the policy's first collection, not merely THAT one happened.
    -- Issuance already pays FIRST_YEAR, so the first collected invoice must accrue no
    -- RENEWAL or the seller is paid twice for the same premium. A boolean cannot carry
    -- that guard safely: once flipped, a REDELIVERED PremiumCollected for that very same
    -- first invoice would read as "not the first any more" and accrue the RENEWAL the
    -- guard exists to prevent -- and sourceRef dedup cannot catch it, because the first
    -- collection deliberately writes no accrual row to collide with. Storing the UUID
    -- makes the guard idempotent: NULL means none yet, equal means a redelivery, and
    -- anything else is a genuine renewal.
    first_invoice_id   UUID,
    lapsed_at          TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number)
);
CREATE INDEX idx_policy_projection_agent ON distribution.policy_projection (agent_id);

ALTER TABLE distribution.policy_projection ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_projection_tenant_isolation ON distribution.policy_projection
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON distribution.policy_projection TO app_role;
