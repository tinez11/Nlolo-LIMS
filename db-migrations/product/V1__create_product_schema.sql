-- Module: product (Product Configuration) -- Deliverable 3 Rev 2 §1
-- Owns: product_definition, product_version, rating_table, benefit_schedule, fund_definition

CREATE SCHEMA IF NOT EXISTS product;

CREATE TABLE product.product_definition (
    product_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    product_code       VARCHAR(30) NOT NULL,
    product_name       VARCHAR(255) NOT NULL,
    category           VARCHAR(30) NOT NULL CHECK (category IN
        ('TERM_LIFE','ENDOWMENT','WHOLE_LIFE','ANNUITY','UNIT_LINKED','GROUP_LIFE','EDUCATION_SAVINGS')),
    status             VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    default_currency   CHAR(3) NOT NULL DEFAULT 'TZS',      -- P1
    ifrs_measurement_model VARCHAR(10) CHECK (ifrs_measurement_model IN ('GMM','PAA')),
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100)
);
CREATE UNIQUE INDEX ux_product_code ON product.product_definition (tenant_id, product_code);
CREATE INDEX idx_product_tenant ON product.product_definition (tenant_id);

-- CHECK: ifrs_measurement_model must be set before status can become ACTIVE
-- (Deliverable 3 invariant) -- enforced via application-layer validation at the
-- publish-version transition, not a DB trigger, to keep the invariant message
-- readable at the API layer (RFC 7807) rather than a raw constraint-violation error.

CREATE TABLE product.product_version (
    product_version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_id          UUID NOT NULL REFERENCES product.product_definition(product_id),
    effective_date      DATE NOT NULL,                      -- P2
    retirement_date      DATE,                                -- P2
    is_active_for_new_business BOOLEAN NOT NULL DEFAULT false,
    grace_period_days   INTEGER NOT NULL,
    max_loan_to_value_percent NUMERIC(5,2),
    surrender_charge_schedule JSONB,                          -- duration-band -> charge% (schema documented in application layer)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100)
    -- Immutable once referenced by an issued policy -- enforced at application layer;
    -- no UPDATE grant needed at DB level since versions are append-only by convention,
    -- but not partitioned (volume here is low: versions per product, not per policy).
);
CREATE INDEX idx_product_version_product ON product.product_version (product_id, effective_date);
CREATE UNIQUE INDEX ux_product_version_active ON product.product_version (product_id) WHERE is_active_for_new_business = true;

CREATE TABLE product.rating_table (
    rating_table_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    factor_type         VARCHAR(30) NOT NULL CHECK (factor_type IN ('AGE','OCCUPATION_CLASS','SMOKER_STATUS','SUM_ASSURED_BAND')),
    band                VARCHAR(50) NOT NULL,
    multiplier          NUMERIC(9,4) NOT NULL
);
CREATE INDEX idx_rating_table_version ON product.rating_table (product_version_id, factor_type);

CREATE TABLE product.benefit_schedule (
    benefit_schedule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    benefit_type        VARCHAR(20) NOT NULL CHECK (benefit_type IN ('DEATH','DISABILITY','CRITICAL_ILLNESS','MATURITY','SURRENDER')),
    calculation_method  VARCHAR(50) NOT NULL,
    conditions          JSONB
);
CREATE INDEX idx_benefit_schedule_version ON product.benefit_schedule (product_version_id);

CREATE TABLE product.fund_definition (
    fund_definition_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    fund_code           VARCHAR(30) NOT NULL,
    current_nav         NUMERIC(19,6) NOT NULL,
    nav_currency        CHAR(3) NOT NULL DEFAULT 'TZS',
    -- Application layer rejects any row here whose product_version's product is not
    -- category = UNIT_LINKED (Deliverable 3 invariant).
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fund_definition_version ON product.fund_definition (product_version_id);

ALTER TABLE product.product_definition ENABLE ROW LEVEL SECURITY;
CREATE POLICY product_definition_tenant_isolation ON product.product_definition
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
ALTER TABLE product.product_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY product_version_tenant_isolation ON product.product_version
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE product.rating_table ENABLE ROW LEVEL SECURITY;
CREATE POLICY rating_table_tenant_isolation ON product.rating_table
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE product.benefit_schedule ENABLE ROW LEVEL SECURITY;
CREATE POLICY benefit_schedule_tenant_isolation ON product.benefit_schedule
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE product.fund_definition ENABLE ROW LEVEL SECURITY;
CREATE POLICY fund_definition_tenant_isolation ON product.fund_definition
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- app_role privileges -- migrations run as the postgres superuser (scripts/migrate.sh),
-- which becomes owner of every object created above; without these explicit grants
-- app_role (the application's runtime DB role) has no access to this schema at all
-- and every request against it fails with "permission denied for schema product"
-- (the exact bug M1's final whole-branch review found and fixed for party/document/
-- refdata/audit -- fixed here from the start instead of waiting for the same review
-- to catch it again).
GRANT USAGE ON SCHEMA product TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA product TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA product GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
