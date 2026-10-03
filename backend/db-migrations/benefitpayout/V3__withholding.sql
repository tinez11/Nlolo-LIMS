-- db-migrations/benefitpayout/V3__withholding.sql
-- Product step 5 (D1): tax withheld from a payout, by rules finance proposes and a SECOND person
-- approves. The rule is the law's, not the product's, so it is platform data -- never a version field
-- and never a rate in code (spec Q8). A rule moves customers' money to the tax authority, which is
-- why it takes two people: the open audit finding is exactly one person changing a live figure.
CREATE TABLE benefitpayout.withholding_rule (
    rule_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    -- The payout kinds it applies to, comma-separated (e.g. 'ANNUITY'). D1 proposes ANNUITY;
    -- extending to others is data. Plain text rather than an array: nothing here maps arrays.
    payout_kinds     VARCHAR(200) NOT NULL
        CHECK (payout_kinds ~ '^(SURVIVAL|MATURITY|INCOME|RETURN_OF_PREMIUM|ANNUITY)(,(SURVIVAL|MATURITY|INCOME|RETURN_OF_PREMIUM|ANNUITY))*$'),
    rate_percent     NUMERIC(9,4) NOT NULL CHECK (rate_percent > 0 AND rate_percent < 100),
    effective_from   DATE NOT NULL,
    effective_to     DATE,
    legal_reference  VARCHAR(200) NOT NULL,
    status           VARCHAR(10) NOT NULL CHECK (status IN ('PROPOSED','APPROVED','WITHDRAWN')),
    proposed_by      VARCHAR(100) NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by      VARCHAR(100),
    approved_at      TIMESTAMPTZ,
    withdrawn_by     VARCHAR(100),
    -- The proposal's Idempotency-Key: a retried proposal returns this rule rather than a second one.
    idempotency_key  VARCHAR(200) NOT NULL,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CHECK (approved_by IS NULL OR approved_by <> proposed_by),
    CHECK ((status = 'APPROVED') = (approved_by IS NOT NULL))
);
CREATE UNIQUE INDEX ux_withholding_rule_key ON benefitpayout.withholding_rule (tenant_id, idempotency_key);

ALTER TABLE benefitpayout.withholding_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY withholding_rule_tenant_isolation ON benefitpayout.withholding_rule
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON benefitpayout.withholding_rule TO app_role;

-- What an approved payout paid, gross and net. Null on every instalment approved before this step
-- (nothing was withheld; the gross IS current_amount). withholding_checked records that the rules
-- were looked at, so "nothing withheld" is distinguishable from "never checked".
ALTER TABLE benefitpayout.payout_instalment
    ADD COLUMN gross_amount        NUMERIC(19,2),
    ADD COLUMN withheld_amount     NUMERIC(19,2),
    ADD COLUMN net_amount          NUMERIC(19,2),
    ADD COLUMN withholding_rule_id UUID REFERENCES benefitpayout.withholding_rule(rule_id),
    ADD COLUMN withholding_checked BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT payout_instalment_withholding_sums CHECK (gross_amount IS NULL
        OR (withheld_amount >= 0 AND net_amount = gross_amount - withheld_amount AND withholding_checked));
