-- db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql
-- Product step 2: the payout engine -- the guide's "tap" (§21.4), the half that pays money OUT
-- while the life assured is still alive. One schema, and no foreign key into another module's
-- tables: a policy number is carried as a value, which is this platform's cross-module rule.
--
-- NOTHING HERE IS PARTITIONED, deliberately. Every date-partitioned table on this platform is
-- registered with pg_partman in _post-migration/configure-pg-partman.sql and listed in
-- ops.platform_readiness(); adding one means editing both, or the health check silently stops
-- covering it. At payout volumes a plain table is right.
CREATE SCHEMA IF NOT EXISTS benefitpayout;
GRANT USAGE ON SCHEMA benefitpayout TO app_role;

-- Groups the instalments of one INCOME row (decision Q7). PENDING_ACTIVATION until its first
-- instalment is approved by two people; SUSPENDED when proof of life falls overdue, which is the
-- guide's own control (§10) and the reason an income stream differs from a run of lump sums.
CREATE TABLE benefitpayout.payout_stream (
    stream_id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                     UUID NOT NULL,
    policy_number                 VARCHAR(20) NOT NULL,
    row_order                     INTEGER NOT NULL,
    status                        VARCHAR(20) NOT NULL DEFAULT 'PENDING_ACTIVATION'
        CHECK (status IN ('PENDING_ACTIVATION','ACTIVE','SUSPENDED','ENDED')),
    proof_of_life_interval_months INTEGER NOT NULL CHECK (proof_of_life_interval_months BETWEEN 1 AND 60),
    proof_of_life_due_date        DATE,
    version                       BIGINT NOT NULL DEFAULT 0,
    UNIQUE (policy_number, row_order)
);

-- One dated amount owed. Reviewer and approver live HERE rather than in a separate review table:
-- there is exactly one review per instalment, so the two-person rule becomes one CHECK on one row
-- -- the same shape policy.surrender_request uses, and for the same reason.
CREATE TABLE benefitpayout.payout_instalment (
    instalment_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    policy_number        VARCHAR(20) NOT NULL,
    kind                 VARCHAR(20) NOT NULL CHECK (kind IN ('SURVIVAL','MATURITY','INCOME','RETURN_OF_PREMIUM')),
    -- Which authored row this came from, so a restatement or a cancellation can find its siblings.
    row_order            INTEGER NOT NULL,
    stream_id            UUID REFERENCES benefitpayout.payout_stream(stream_id),
    due_date             DATE NOT NULL,
    -- Null ONLY on a RETURN_OF_PREMIUM instalment until it falls due: it is valued off premiums
    -- actually collected, which nobody knows at issue.
    original_amount      NUMERIC(19,2) CHECK (original_amount IS NULL OR original_amount > 0),
    -- The figure actually payable now. Paid-up restates this and leaves original_amount alone, so
    -- the reduction is legible rather than silent.
    current_amount       NUMERIC(19,2) CHECK (current_amount IS NULL OR current_amount >= 0),
    currency             CHAR(3) NOT NULL DEFAULT 'TZS',
    restatement_reason   VARCHAR(200),
    status               VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED','DUE','ON_HOLD','REVIEWED','APPROVED','PAID','FAILED','IN_DOUBT','CANCELLED')),
    status_reason        VARCHAR(200),
    payee_ref            VARCHAR(200),
    proof_of_life_method VARCHAR(20) CHECK (proof_of_life_method IN ('IN_PERSON','PHONE_OR_VIDEO','LIFE_CERTIFICATE')),
    proof_of_life_document_id UUID,
    reviewed_by          VARCHAR(100),
    reviewed_at          TIMESTAMPTZ,
    approved_by          VARCHAR(100),
    approved_at          TIMESTAMPTZ,
    payment_run_id       UUID,
    disbursement_id      UUID,
    attempts             INTEGER NOT NULL DEFAULT 0,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payout_two_person CHECK (approved_by IS NULL OR approved_by <> reviewed_by),
    -- One instalment per row per date: a redelivered PolicyIssued cannot double the schedule.
    UNIQUE (policy_number, row_order, due_date)
);
CREATE INDEX idx_payout_instalment_policy ON benefitpayout.payout_instalment (policy_number, due_date);
CREATE INDEX idx_payout_instalment_due ON benefitpayout.payout_instalment (status, due_date);

-- What billing has reported about one policy's premiums, kept here because benefitpayout may not
-- depend on billing (spec §4) -- the figures arrive by event and this is where they land.
CREATE TABLE benefitpayout.premium_tally (
    policy_number        VARCHAR(20) PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    premium_frequency    VARCHAR(12) NOT NULL,
    issue_date           DATE NOT NULL,
    premium_paying_until DATE,
    premiums_collected   NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (premiums_collected >= 0),
    currency             CHAR(3) NOT NULL DEFAULT 'TZS',
    paid_to_date         DATE,
    version              BIGINT NOT NULL DEFAULT 0
);

-- One day's batch of income instalments for one tenant (Q7): the system prepares it, one person
-- approves it, as a payroll is signed off.
CREATE TABLE benefitpayout.payment_run (
    payment_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    run_date       DATE NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PREPARED' CHECK (status IN ('PREPARED','APPROVED')),
    approved_by    VARCHAR(100),
    approved_at    TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, run_date)
);

CREATE TABLE benefitpayout.free_look_cancellation (
    cancellation_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    policy_number      VARCHAR(20) NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED','APPROVED','PAID','FAILED','IN_DOUBT')),
    premiums_collected NUMERIC(19,2) NOT NULL CHECK (premiums_collected >= 0),
    refund_amount      NUMERIC(19,2) NOT NULL CHECK (refund_amount >= 0),
    currency           CHAR(3) NOT NULL DEFAULT 'TZS',
    payee_ref          VARCHAR(200) NOT NULL,
    requested_by       VARCHAR(100) NOT NULL,
    requested_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by        VARCHAR(100),
    approved_at        TIMESTAMPTZ,
    disbursement_id    UUID,
    version            BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT free_look_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
-- At most one live cancellation per policy, the shape ux_surrender_request_live uses.
CREATE UNIQUE INDEX ux_free_look_live ON benefitpayout.free_look_cancellation (policy_number)
    WHERE status IN ('REQUESTED','APPROVED');

-- What the insurer kept back, itemised. The platform records no underwriting costs anywhere, so
-- the person requesting the cancellation states them and a second person approves them with the
-- refund they produce (decision Q4).
CREATE TABLE benefitpayout.free_look_deduction (
    deduction_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    cancellation_id UUID NOT NULL REFERENCES benefitpayout.free_look_cancellation(cancellation_id),
    description     VARCHAR(200) NOT NULL,
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    document_id     UUID
);

-- Selectors for the drains: cross-tenant and SECURITY DEFINER, returning IDS ONLY -- never a party,
-- a policy's sum assured or any business figure. The drain sets the tenant from each row and does
-- everything else under ordinary RLS. Exactly the policy.policies_due_to_expire() shape, and for
-- the reason it documents: a sweep runs with no request and therefore no tenant, so it must see
-- past RLS, and the narrowest thing it can return is an id.
CREATE OR REPLACE FUNCTION benefitpayout.instalments_falling_due()
RETURNS TABLE (instalment_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT i.instalment_id, i.tenant_id FROM benefitpayout.payout_instalment i
     WHERE i.status = 'SCHEDULED' AND i.due_date <= current_date
     ORDER BY i.due_date LIMIT 500;
$$;

CREATE OR REPLACE FUNCTION benefitpayout.tenants_with_stream_instalments_due()
RETURNS TABLE (tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT DISTINCT i.tenant_id FROM benefitpayout.payout_instalment i
      JOIN benefitpayout.payout_stream s ON s.stream_id = i.stream_id
     WHERE i.status = 'DUE' AND s.status = 'ACTIVE' AND i.payment_run_id IS NULL;
$$;

CREATE OR REPLACE FUNCTION benefitpayout.streams_due_for_proof_of_life()
RETURNS TABLE (stream_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT s.stream_id, s.tenant_id FROM benefitpayout.payout_stream s
     WHERE s.status = 'ACTIVE' AND s.proof_of_life_due_date < current_date LIMIT 500;
$$;

-- Postgres grants EXECUTE to PUBLIC by default; app_role genuinely needs these (the drains run as
-- app_role), so each is revoked and re-granted deliberately.
REVOKE EXECUTE ON FUNCTION benefitpayout.instalments_falling_due() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION benefitpayout.tenants_with_stream_instalments_due() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION benefitpayout.streams_due_for_proof_of_life() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION benefitpayout.instalments_falling_due() TO app_role;
GRANT EXECUTE ON FUNCTION benefitpayout.tenants_with_stream_instalments_due() TO app_role;
GRANT EXECUTE ON FUNCTION benefitpayout.streams_due_for_proof_of_life() TO app_role;

-- No DELETE anywhere: a payout row is never deleted, only cancelled. Money owed and then withdrawn
-- is a fact the record has to keep.
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['payout_stream','payout_instalment','premium_tally','payment_run',
                             'free_look_cancellation','free_look_deduction'] LOOP
        EXECUTE format('ALTER TABLE benefitpayout.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON benefitpayout.%I USING (tenant_id = '
            'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON benefitpayout.%I TO app_role', t);
    END LOOP;
END $$;
