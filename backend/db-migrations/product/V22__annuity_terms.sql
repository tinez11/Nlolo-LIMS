-- db-migrations/product/V22__annuity_terms.sql
-- Product step 5 (D1): an ANNUITY version's forms, rate grids and frequency factors.
--
-- Separate tables, for V19's and V21's reason, AND read only after the product's category says
-- ANNUITY (ProductApiImpl.resolveAnnuityPlan), so no other version and no existing test class ever
-- reads them -- V21's 71-file migration-list sweep is the cost that gate avoids.
CREATE TABLE product.version_annuity_terms (
    product_version_id            UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id                     UUID NOT NULL,
    -- No default, on purpose (spec Q5): whether the first payment is a period after purchase or on
    -- it is a contract term the version states.
    timing                        VARCHAR(10) NOT NULL CHECK (timing IN ('ARREARS','ADVANCE')),
    proof_of_life_interval_months INTEGER NOT NULL CHECK (proof_of_life_interval_months BETWEEN 1 AND 24),
    joint_age_difference_min      INTEGER,
    joint_age_difference_max      INTEGER,
    basis_reference               VARCHAR(100) NOT NULL,
    basis_date                    DATE NOT NULL,
    CHECK ((joint_age_difference_min IS NULL) = (joint_age_difference_max IS NULL)),
    CHECK (joint_age_difference_min IS NULL OR joint_age_difference_min <= joint_age_difference_max)
);

-- One combination of four settings (spec Q2): no form is special-cased in code.
CREATE TABLE product.annuity_form (
    annuity_form_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    form_code           VARCHAR(30) NOT NULL,
    guarantee_years     INTEGER NOT NULL CHECK (guarantee_years BETWEEN 0 AND 30),
    joint               BOOLEAN NOT NULL,
    survivor_percent    NUMERIC(9,4),
    escalation_percent  NUMERIC(9,4) NOT NULL CHECK (escalation_percent BETWEEN 0 AND 10),
    capital_protected   BOOLEAN NOT NULL,
    rate_basis          VARCHAR(10) NOT NULL CHECK (rate_basis IN ('UNISEX','BY_SEX')),
    CHECK ((joint AND survivor_percent BETWEEN 1 AND 100) OR (NOT joint AND survivor_percent IS NULL))
);
CREATE UNIQUE INDEX ux_annuity_form_code ON product.annuity_form (product_version_id, form_code);

-- Annual income per 1,000 of purchase price. difference = annuitant age - joint-life age.
CREATE TABLE product.annuity_rate_row (
    annuity_rate_row_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL,
    annuity_form_id        UUID NOT NULL REFERENCES product.annuity_form(annuity_form_id),
    sex                    VARCHAR(10) CHECK (sex IN ('FEMALE','MALE')),
    age                    INTEGER NOT NULL CHECK (age BETWEEN 0 AND 120),
    age_difference_from    INTEGER,
    age_difference_to      INTEGER,
    annual_rate_per_mille  NUMERIC(9,4) NOT NULL CHECK (annual_rate_per_mille > 0),
    CHECK ((age_difference_from IS NULL) = (age_difference_to IS NULL)),
    CHECK (age_difference_from IS NULL OR age_difference_from <= age_difference_to)
);
CREATE INDEX ix_annuity_rate_row_form ON product.annuity_rate_row (annuity_form_id, age);

CREATE TABLE product.annuity_frequency (
    annuity_frequency_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id   UUID NOT NULL REFERENCES product.product_version(product_version_id),
    tenant_id            UUID NOT NULL,
    frequency            VARCHAR(12) NOT NULL CHECK (frequency IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
    factor               NUMERIC(9,4) NOT NULL CHECK (factor > 0 AND factor <= 1),
    CHECK (frequency <> 'ANNUAL' OR factor = 1)
);
CREATE UNIQUE INDEX ux_annuity_frequency ON product.annuity_frequency (product_version_id, frequency);

ALTER TABLE product.version_annuity_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_annuity_terms_tenant_isolation ON product.version_annuity_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.annuity_form ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_form_tenant_isolation ON product.annuity_form
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.annuity_rate_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_rate_row_tenant_isolation ON product.annuity_rate_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.annuity_frequency ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_frequency_tenant_isolation ON product.annuity_frequency
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_annuity_terms, product.annuity_form,
    product.annuity_rate_row, product.annuity_frequency TO app_role;
