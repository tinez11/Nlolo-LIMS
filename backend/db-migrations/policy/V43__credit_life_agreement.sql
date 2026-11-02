-- db-migrations/policy/V43__credit_life_agreement.sql
-- A lender's yearly agreement (2026-10-30, the user: "after they enter a contract ... the premium and the commission of
-- the bank which they agreed to for a year"). One row per year a credit-life scheme runs: the rate and how it is applied,
-- the least a loan pays, the oldest borrower and the shortest and longest loan it takes, the lender's commission, and
-- whether cover includes the loan's interest. A borrower is priced on the agreement in force on the day their loan
-- started, so a renewal never re-prices anyone already on cover.
--
-- The scheme's own rate and basis (V25) stay: a scheme with no agreement is priced on them, as every scheme before this
-- one was.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

CREATE TABLE IF NOT EXISTS policy.credit_life_agreement (
    agreement_id            UUID          PRIMARY KEY,
    tenant_id               UUID          NOT NULL,
    policy_number           VARCHAR(20)   NOT NULL REFERENCES policy.policy (policy_number),
    valid_from              DATE          NOT NULL,
    valid_to                DATE          NOT NULL,
    premium_rate_percent    NUMERIC(9,4)  NOT NULL,
    premium_basis           VARCHAR(30)   NOT NULL,
    minimum_premium         NUMERIC(19,2) NOT NULL DEFAULT 0,
    max_age                 INTEGER       NOT NULL,
    min_term_months         INTEGER       NOT NULL DEFAULT 12,
    max_term_months         INTEGER       NOT NULL,
    commission_percent      NUMERIC(5,2)  NOT NULL DEFAULT 0,
    cover_includes_interest BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by              VARCHAR(100)  NOT NULL,
    CONSTRAINT chk_cla_dates CHECK (valid_to >= valid_from),
    CONSTRAINT chk_cla_rate CHECK (premium_rate_percent > 0 AND premium_rate_percent <= 100),
    CONSTRAINT chk_cla_basis CHECK (premium_basis IN
        ('FLAT_ON_PRINCIPAL', 'PER_ANNUM_ON_PRINCIPAL', 'ANNUAL_ON_DECLINING_BALANCE', 'WHOLE_YEARS_ROUNDED_UP')),
    CONSTRAINT chk_cla_minimum CHECK (minimum_premium >= 0),
    CONSTRAINT chk_cla_age CHECK (max_age BETWEEN 18 AND 100),
    CONSTRAINT chk_cla_terms CHECK (min_term_months >= 1 AND max_term_months >= min_term_months AND max_term_months <= 480),
    -- TIRA's cap on credit-life commission (Circular 029-2015 addendum, March 2025): at most 15% of premium.
    CONSTRAINT chk_cla_commission CHECK (commission_percent >= 0 AND commission_percent <= 15)
);

CREATE INDEX IF NOT EXISTS ix_cla_scheme ON policy.credit_life_agreement (tenant_id, policy_number, valid_from);

-- Two agreements in force on one day would price a loan two ways. Refused by the application, which locks the scheme
-- while it adds one: an exclusion constraint would need btree_gist, an extension nothing else here installs.

ALTER TABLE policy.credit_life_agreement ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS credit_life_agreement_tenant_isolation ON policy.credit_life_agreement;
CREATE POLICY credit_life_agreement_tenant_isolation ON policy.credit_life_agreement
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON policy.credit_life_agreement TO app_role;

-- The rule the user's new template uses: a year's rate for every year the loan runs, a part year counted whole.
ALTER TABLE policy.group_scheme DROP CONSTRAINT IF EXISTS group_scheme_premium_basis_check;
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT group_scheme_premium_basis_check
        CHECK (premium_basis IS NULL OR premium_basis IN
            ('FLAT_ON_PRINCIPAL', 'PER_ANNUM_ON_PRINCIPAL', 'ANNUAL_ON_DECLINING_BALANCE', 'WHOLE_YEARS_ROUNDED_UP'));
