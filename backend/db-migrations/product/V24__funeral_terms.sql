-- db-migrations/product/V24__funeral_terms.sql
-- Family funeral cover (spec 2026-10-04): one policy covering a main member and their family. A
-- FUNERAL version's plans, each plan's benefit per role, its age-banded premium table, its role rules
-- and its claim rules. Absent on every other version -- its absence IS that -- and read only after the
-- product's category says FUNERAL, so no other test class needs this migration.
ALTER TABLE product.product_definition
    DROP CONSTRAINT IF EXISTS product_definition_category_check;
ALTER TABLE product.product_definition
    ADD CONSTRAINT product_definition_category_check
    CHECK (category IN ('TERM_LIFE','ENDOWMENT','WHOLE_LIFE','ANNUITY','UNIT_LINKED',
                        'GROUP_LIFE','EDUCATION_SAVINGS','CREDIT_LIFE','FUNERAL'));

CREATE TABLE product.funeral_terms (
    product_version_id      UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id               UUID NOT NULL,
    -- The oldest age the premium table must price for a role whose cover never stops for age.
    max_priced_age          INTEGER NOT NULL CHECK (max_priced_age BETWEEN 1 AND 120),
    -- Null = no waiting period. Absent is not zero (ExclusionPeriods' reason).
    waiting_period_months   INTEGER CHECK (waiting_period_months IS NULL OR waiting_period_months > 0),
    accident_waives_waiting BOOLEAN NOT NULL,
    dependant_claim_payee   VARCHAR(30) NOT NULL
        CHECK (dependant_claim_payee IN ('MAIN_MEMBER','MAIN_MEMBER_BENEFICIARY')),
    on_main_member_death    VARCHAR(30) NOT NULL
        CHECK (on_main_member_death IN ('POLICY_ENDS','SPOUSE_TAKES_OVER')),
    free_cover_to_paid_date BOOLEAN NOT NULL
);

CREATE TABLE product.funeral_plan (
    funeral_plan_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id UUID NOT NULL REFERENCES product.funeral_terms(product_version_id),
    tenant_id          UUID NOT NULL,
    plan_code          VARCHAR(20) NOT NULL,
    name               VARCHAR(100) NOT NULL,
    UNIQUE (product_version_id, plan_code)
);

CREATE TABLE product.funeral_plan_benefit (
    funeral_plan_benefit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id      UUID NOT NULL,
    tenant_id               UUID NOT NULL,
    plan_code               VARCHAR(20) NOT NULL,
    role                    VARCHAR(20) NOT NULL
        CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    benefit                 NUMERIC(19,2) NOT NULL CHECK (benefit > 0),
    UNIQUE (product_version_id, plan_code, role),
    FOREIGN KEY (product_version_id, plan_code) REFERENCES product.funeral_plan(product_version_id, plan_code)
);

CREATE TABLE product.funeral_premium (
    funeral_premium_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id UUID NOT NULL,
    tenant_id          UUID NOT NULL,
    plan_code          VARCHAR(20) NOT NULL,
    role               VARCHAR(20) NOT NULL
        CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    age_from           INTEGER NOT NULL CHECK (age_from >= 0),
    age_to             INTEGER NOT NULL,
    -- A FIXED yearly amount per life (spec Q5), not a rate per mille.
    yearly_premium     NUMERIC(19,2) NOT NULL CHECK (yearly_premium > 0),
    CHECK (age_to >= age_from),
    FOREIGN KEY (product_version_id, plan_code) REFERENCES product.funeral_plan(product_version_id, plan_code)
);
CREATE INDEX idx_funeral_premium_lookup
    ON product.funeral_premium (product_version_id, plan_code, role, age_from);

CREATE TABLE product.funeral_role_rule (
    funeral_role_rule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id   UUID NOT NULL REFERENCES product.funeral_terms(product_version_id),
    tenant_id            UUID NOT NULL,
    role                 VARCHAR(20) NOT NULL
        CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    max_lives            INTEGER NOT NULL CHECK (max_lives > 0),
    min_entry_age        INTEGER NOT NULL CHECK (min_entry_age >= 0),
    max_entry_age        INTEGER NOT NULL,
    -- Null = cover never stops for age.
    cover_stop_age       INTEGER,
    -- A child marked as a student is covered to this age instead; null = no student extension.
    student_stop_age     INTEGER,
    UNIQUE (product_version_id, role),
    CHECK (max_entry_age >= min_entry_age),
    CHECK (cover_stop_age IS NULL OR cover_stop_age > max_entry_age),
    CHECK (student_stop_age IS NULL
        OR (role = 'CHILD' AND cover_stop_age IS NOT NULL AND student_stop_age > cover_stop_age)),
    -- One main member and one spouse (spec Q2).
    CHECK (role NOT IN ('MAIN_MEMBER','SPOUSE') OR max_lives = 1)
);

ALTER TABLE product.funeral_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_terms_tenant_isolation ON product.funeral_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.funeral_plan ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_plan_tenant_isolation ON product.funeral_plan
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.funeral_plan_benefit ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_plan_benefit_tenant_isolation ON product.funeral_plan_benefit
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.funeral_premium ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_premium_tenant_isolation ON product.funeral_premium
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.funeral_role_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_role_rule_tenant_isolation ON product.funeral_role_rule
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.funeral_terms, product.funeral_plan,
    product.funeral_plan_benefit, product.funeral_premium, product.funeral_role_rule TO app_role;
