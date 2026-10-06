-- db-migrations/reinsurance/V5__bordereau.sql
-- IFRS 17 I3c (posting guide K-01..K-03): reinsurance held posts from a MONTHLY BORDEREAU per treaty, not from the
-- cession at activation (which posted the ceded SUM ASSURED as if it were premium owed).
--
-- User answers 2026-10-06: ceded premium on original terms (the cession's share of the policy's own premium, as a
-- monthly amount); a full month for every month cover is in force at any point; a treaty's commission not
-- contingent on claims (K-02) is stated on the treaty, 0 allowed; an XOL treaty may carry a flat annual premium,
-- charged 1/12 a month.

-- 1. Treaty terms the bordereau needs. Existing treaties: commission 0, never the guide's illustrative 20%.
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN commission_percent NUMERIC(5,2) NOT NULL DEFAULT 0;
ALTER TABLE reinsurance.reinsurance_treaty ALTER COLUMN commission_percent DROP DEFAULT;
ALTER TABLE reinsurance.reinsurance_treaty
    ADD CONSTRAINT treaty_commission_percent_range CHECK (commission_percent >= 0 AND commission_percent <= 100);
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN xol_annual_premium NUMERIC(19,2);
ALTER TABLE reinsurance.reinsurance_treaty
    ADD CONSTRAINT treaty_xol_annual_premium_only_xol
    CHECK (xol_annual_premium IS NULL OR (treaty_type = 'XOL' AND xol_annual_premium > 0));

-- 2. The share of the policy's premium that travels with the risk: a quota share's percent, a surplus's ceded risk
--    over the sum assured. Backfilled from the rows' own figures.
ALTER TABLE reinsurance.cession ADD COLUMN premium_share NUMERIC(12,10);
UPDATE reinsurance.cession c
   SET premium_share = round(c.ceded_amount / p.sum_assured_amount, 10)
  FROM reinsurance.policy_projection p
 WHERE p.tenant_id = c.tenant_id AND p.policy_number = c.policy_number AND c.premium_share IS NULL;
ALTER TABLE reinsurance.cession
    ADD CONSTRAINT cession_premium_share_range CHECK (premium_share IS NULL OR (premium_share > 0 AND premium_share <= 1));

-- 3. What reinsurance must know of a policy month by month: how often it pays, and when its premiums ended
--    (paid up, premium term over) -- on original terms the reinsurer's premium stops with the policy's.
ALTER TABLE reinsurance.policy_projection ADD COLUMN premium_frequency VARCHAR(20);
ALTER TABLE reinsurance.policy_projection ADD COLUMN premiums_end_on DATE;

-- 4. When each policy was on risk. Activation opens a period; lapse, surrender (a death claim closes a policy as
--    surrendered), maturity, expiry and an annuity's end close it; reinstatement opens another. A free-look
--    cancellation voids cover from inception, so its periods are deleted.
CREATE TABLE reinsurance.cover_period (
    cover_period_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    policy_number   VARCHAR(20) NOT NULL,
    starts_on       DATE NOT NULL,
    ends_on         DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cover_period_order CHECK (ends_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX idx_cover_period_policy ON reinsurance.cover_period (tenant_id, policy_number);
-- At most one open period per policy: a redelivered activation or reinstatement opens nothing new.
CREATE UNIQUE INDEX ux_cover_period_one_open ON reinsurance.cover_period (tenant_id, policy_number) WHERE ends_on IS NULL;

-- 5. One bordereau per treaty and month, final once written; its lines are the policies charged (and the XOL flat
--    premium) plus the recoveries recorded that month, which are only matched (K-03: no posting).
CREATE TABLE reinsurance.bordereau (
    bordereau_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    treaty_id     UUID NOT NULL REFERENCES reinsurance.reinsurance_treaty (treaty_id),
    period        VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    currency      CHAR(3) NOT NULL,
    policy_count  INTEGER NOT NULL CHECK (policy_count >= 0),
    premium       NUMERIC(19,2) NOT NULL CHECK (premium >= 0),
    commission    NUMERIC(19,2) NOT NULL CHECK (commission >= 0),
    recoveries    NUMERIC(19,2) NOT NULL CHECK (recoveries >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(100) NOT NULL
);
CREATE UNIQUE INDEX ux_bordereau_once ON reinsurance.bordereau (tenant_id, treaty_id, period);

CREATE TABLE reinsurance.bordereau_line (
    line_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bordereau_id    UUID NOT NULL REFERENCES reinsurance.bordereau (bordereau_id),
    tenant_id       UUID NOT NULL,
    line_type       VARCHAR(20) NOT NULL CHECK (line_type IN ('PREMIUM', 'XOL_PREMIUM', 'RECOVERY')),
    policy_number   VARCHAR(20),
    claim_id        UUID,
    premium_share   NUMERIC(12,10),
    policy_premium  NUMERIC(19,2),
    premium         NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (premium >= 0),
    commission      NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (commission >= 0),
    recovery        NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (recovery >= 0)
);
CREATE INDEX idx_bordereau_line_bordereau ON reinsurance.bordereau_line (bordereau_id);

ALTER TABLE reinsurance.cover_period ENABLE ROW LEVEL SECURITY;
CREATE POLICY cover_period_tenant_isolation ON reinsurance.cover_period
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE reinsurance.bordereau ENABLE ROW LEVEL SECURITY;
CREATE POLICY bordereau_tenant_isolation ON reinsurance.bordereau
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE reinsurance.bordereau_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY bordereau_line_tenant_isolation ON reinsurance.bordereau_line
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON reinsurance.cover_period TO app_role;
GRANT SELECT, INSERT ON reinsurance.bordereau, reinsurance.bordereau_line TO app_role;

-- 6. Every (treaty, month) before p_month_start (the first day of the current month in Dar es Salaam) inside the
--    treaty's effective window with no bordereau yet, across tenants: the job runs under no tenant and builds each
--    under its own.
CREATE OR REPLACE FUNCTION reinsurance.bordereaux_due(p_month_start DATE)
RETURNS TABLE (tenant_id UUID, treaty_id UUID, period TEXT)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT t.tenant_id, t.treaty_id, to_char(m, 'YYYY-MM')
      FROM reinsurance.reinsurance_treaty t
      CROSS JOIN LATERAL generate_series(date_trunc('month', t.effective_from)::date,
                                         (p_month_start - INTERVAL '1 month')::date, INTERVAL '1 month') AS m
     WHERE (t.effective_to IS NULL OR m <= t.effective_to)
       AND NOT EXISTS (SELECT 1 FROM reinsurance.bordereau b
                        WHERE b.tenant_id = t.tenant_id AND b.treaty_id = t.treaty_id
                          AND b.period = to_char(m, 'YYYY-MM'))
     ORDER BY m, t.tenant_id, t.treaty_id
     LIMIT 200;
$$;
REVOKE EXECUTE ON FUNCTION reinsurance.bordereaux_due(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reinsurance.bordereaux_due(DATE) TO app_role;

-- 7. Backfill the policies reinsurance already knows, from policy's own record where that schema is present (a
--    module test may apply reinsurance without it). Cover starts at commencement (else issue); a closed policy's
--    period ends on the date its closure is recorded; a free-look cancellation was never on risk.
DO $$
BEGIN
    -- The columns read below arrive with policy V6/V27/V30; a test applying an older policy chain gets no backfill.
    IF to_regclass('policy.policy') IS NULL OR NOT EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'policy' AND table_name = 'policy' AND column_name = 'surrender_effective_date') THEN
        RETURN;
    END IF;
    UPDATE reinsurance.policy_projection r
       SET premium_frequency = p.premium_frequency
      FROM policy.policy p
     WHERE p.tenant_id = r.tenant_id AND p.policy_number = r.policy_number AND r.premium_frequency IS NULL;
    INSERT INTO reinsurance.cover_period (tenant_id, policy_number, starts_on, ends_on)
    SELECT tenant_id, policy_number, starts_on,
           CASE WHEN ends_on IS NULL THEN NULL ELSE GREATEST(ends_on, starts_on) END
      FROM (SELECT r.tenant_id, r.policy_number, COALESCE(p.commencement_date, p.issue_date) AS starts_on,
                   CASE
                       WHEN p.status IN ('ACTIVE', 'REINSTATED', 'PAID_UP', 'SUSPENDED') THEN NULL
                       WHEN p.status = 'LAPSED' THEN (COALESCE(p.lapsed_at, p.updated_at) AT TIME ZONE 'Africa/Dar_es_Salaam')::date
                       WHEN p.status = 'SURRENDERED' THEN COALESCE(p.surrender_effective_date,
                           (p.updated_at AT TIME ZONE 'Africa/Dar_es_Salaam')::date)
                       WHEN p.status IN ('MATURED', 'EXPIRED') THEN COALESCE(p.maturity_date,
                           (p.updated_at AT TIME ZONE 'Africa/Dar_es_Salaam')::date)
                       ELSE (p.updated_at AT TIME ZONE 'Africa/Dar_es_Salaam')::date
                   END AS ends_on
              FROM reinsurance.policy_projection r
              JOIN policy.policy p ON p.tenant_id = r.tenant_id AND p.policy_number = r.policy_number
             WHERE p.status NOT IN ('PROPOSED', 'NOT_TAKEN_UP', 'CANCELLED_FREE_LOOK')
               AND NOT EXISTS (SELECT 1 FROM reinsurance.cover_period c
                                WHERE c.tenant_id = r.tenant_id AND c.policy_number = r.policy_number)) s;
    UPDATE reinsurance.policy_projection r
       SET premiums_end_on = (p.updated_at AT TIME ZONE 'Africa/Dar_es_Salaam')::date
      FROM policy.policy p
     WHERE p.tenant_id = r.tenant_id AND p.policy_number = r.policy_number AND p.status = 'PAID_UP';
END;
$$;
