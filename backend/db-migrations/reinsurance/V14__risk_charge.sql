-- 2026-11-12, finance finding 166: a treaty takes the risk, never the savings part. A unit-linked policy's risk premium
-- is the cost of insurance the monthly charge run takes from its units (unitlinked.ChargesTaken); each bordereau charges
-- the treaty's share of what was taken that month. One row per charge, never edited.
CREATE TABLE reinsurance.risk_charge (
    tenant_id      UUID NOT NULL,
    source_ref     VARCHAR(200) NOT NULL,
    policy_number  VARCHAR(50) NOT NULL,
    charge_date    DATE NOT NULL,
    amount         NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency       CHAR(3) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_ref)
);
CREATE INDEX ix_risk_charge_month ON reinsurance.risk_charge (tenant_id, charge_date);

ALTER TABLE reinsurance.risk_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY risk_charge_tenant_isolation ON reinsurance.risk_charge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON reinsurance.risk_charge TO app_role;
