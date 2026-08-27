-- M13 -- the base rates premiums are actually computed from.
--
-- Deliverable 1 §2.1 gave Product Configuration responsibility for "premium
-- rating factors, mortality/morbidity tables" and named RatingTable a core
-- aggregate. What V1 shipped is product.rating_table holding
-- (factor_type, band, multiplier) -- the FACTORS, with no base to multiply. A set
-- of multipliers cannot produce a premium, so no premium has ever been
-- computable on this platform; ManualIssueRequest takes premiumAmount from the
-- caller instead.
--
-- This is the missing half. Named base_rate_table, not mortality_table: these are
-- rates an actuary authors and signs, not a qx table the platform derives a
-- premium from (that would additionally require interest, expense and profit
-- assumptions, which touch IFRS 17 measurement -- still Deliverable 1 open
-- question #4). Calling it a mortality table would imply a derivation that does
-- not happen, and `mortality_table` is left unclaimed for whoever builds it.

CREATE TABLE product.base_rate_table (
    base_rate_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    -- VARCHAR(50) deliberately MATCHES rating_table.band, not a tighter guess:
    -- the two share one age-band vocabulary, so a band that fits there must fit
    -- here. A column too short for a value the system already produces is the
    -- exact defect shape found at M7 (VARCHAR(15) vs PAYOUT_REQUESTED, 16 chars)
    -- and again at M9 (VARCHAR(30) vs a 31-char event name).
    age_band            VARCHAR(50) NOT NULL,
    sex                 VARCHAR(10) NOT NULL CHECK (sex IN ('FEMALE','MALE')),
    smoker_status       VARCHAR(20) NOT NULL CHECK (smoker_status IN ('SMOKER','NON_SMOKER','UNKNOWN')),
    -- Rate per TZS 1,000 of sum assured per year. The CHECK is on the table and
    -- not only in bean validation: a zero or negative rate is a free policy or
    -- one that pays the customer, and that must be unrepresentable at rest.
    rate_per_mille      NUMERIC(10,4) NOT NULL CHECK (rate_per_mille > 0),
    -- One rate per cell. Two rows for the same cell would make pricing depend on
    -- row order, which is a silent mispricing rather than an error.
    UNIQUE (product_version_id, age_band, sex, smoker_status)
);
CREATE INDEX idx_base_rate_version ON product.base_rate_table (product_version_id, age_band);

-- RLS is NOT covered by V1's ALTER DEFAULT PRIVILEGES -- that grants table
-- privileges to app_role on future tables, and says nothing about row policies.
-- A new tenant-scoped table without these two statements is readable across
-- tenants by a role that passes every grant check: the same class of gap M1's
-- review found for whole schemas and M3's found again at the partition level.
ALTER TABLE product.base_rate_table ENABLE ROW LEVEL SECURITY;
CREATE POLICY base_rate_table_tenant_isolation ON product.base_rate_table
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- Explicit rather than relying on V1's default privileges. They should cover a
-- table created by the same superuser in a later migration, but "should" is how
-- the permission-denied-for-schema bug reached production once already, and an
-- idempotent GRANT costs nothing.
GRANT SELECT, INSERT, UPDATE, DELETE ON product.base_rate_table TO app_role;
