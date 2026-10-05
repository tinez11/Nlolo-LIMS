-- db-migrations/product/V26__unit_linked_options.sql
-- Product step 6 (U2): what a UNIT_LINKED version offers beyond U1 -- fund switches, partial withdrawals, top-ups
-- and the surrender charge. Optional and 1:1 (plan R1): a version with no row offers none of them and charges
-- nothing, so every version published under U1 keeps exactly the contract it was sold with and NOTHING here is
-- backfilled. Inside a row each feature is off while its own pair of columns is null.
CREATE TABLE product.unit_linked_options (
    product_version_id              UUID PRIMARY KEY REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id                       UUID NOT NULL,
    free_switches_per_year          INTEGER CHECK (free_switches_per_year IS NULL OR free_switches_per_year >= 0),
    switch_fee                      NUMERIC(19,2) CHECK (switch_fee IS NULL OR switch_fee >= 0),
    minimum_withdrawal              NUMERIC(19,2) CHECK (minimum_withdrawal IS NULL OR minimum_withdrawal > 0),
    minimum_remaining_value         NUMERIC(19,2) CHECK (minimum_remaining_value IS NULL OR minimum_remaining_value >= 0),
    -- Spec Q4: configurable per version, default no.
    withdrawal_reduces_sum_assured  BOOLEAN NOT NULL DEFAULT FALSE,
    top_up_allocation_percent       NUMERIC(7,4) CHECK (top_up_allocation_percent IS NULL
                                        OR (top_up_allocation_percent > 0 AND top_up_allocation_percent <= 100)),
    minimum_top_up                  NUMERIC(19,2) CHECK (minimum_top_up IS NULL OR minimum_top_up > 0),
    CHECK ((free_switches_per_year IS NULL) = (switch_fee IS NULL)),
    CHECK ((minimum_withdrawal IS NULL) = (minimum_remaining_value IS NULL)),
    CHECK ((top_up_allocation_percent IS NULL) = (minimum_top_up IS NULL))
);

-- The surrender charge by policy year (spec Q6/Q7): on surrenders, withdrawals and non-payment lapses only.
CREATE TABLE product.unit_linked_surrender_charge (
    unit_linked_surrender_charge_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id              UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id                       UUID NOT NULL,
    from_year                       INTEGER NOT NULL CHECK (from_year >= 1),
    to_year                         INTEGER CHECK (to_year IS NULL OR to_year >= from_year),
    charge_percent                  NUMERIC(7,4) NOT NULL CHECK (charge_percent >= 0 AND charge_percent <= 100),
    UNIQUE (product_version_id, from_year)
);

ALTER TABLE product.unit_linked_options ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_options_tenant_isolation ON product.unit_linked_options
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.unit_linked_surrender_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_surrender_charge_tenant_isolation ON product.unit_linked_surrender_charge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON product.unit_linked_options, product.unit_linked_surrender_charge TO app_role;

-- A version's terms are its contract with every policy sold on it (spec A2): once written, never changed. A version
-- is only ever written when it is published, so every row of these tables is a published one, and the refusal is
-- unconditional. A change of terms is a new version, which new business is sold on and existing policies never see.
CREATE OR REPLACE FUNCTION product.refuse_unit_linked_terms_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'A published unit-linked version''s terms are never changed (% on %); publish a new version',
        TG_OP, TG_TABLE_NAME;
END $$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['unit_linked_terms','unit_linked_fund','unit_linked_allocation_band',
                             'unit_linked_mortality','unit_linked_premium_minimum',
                             'unit_linked_options','unit_linked_surrender_charge'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON product.%I
                        FOR EACH ROW EXECUTE FUNCTION product.refuse_unit_linked_terms_change()',
                       t || '_immutable', t);
    END LOOP;
END $$;
