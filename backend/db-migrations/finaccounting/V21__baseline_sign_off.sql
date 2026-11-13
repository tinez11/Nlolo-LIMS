-- 2026-11-12, finance findings 99 and 66: the accounting-policy baseline was "approved" by the system, by no person, and
-- read "pending actuary and auditor sign-off" for ever. Finance now signs it off by name (the user: "99 finance but can
-- add role actuary"), and an actuary may co-sign. One row per tenant and signer; never edited or removed. The baseline
-- stays in force meanwhile -- nothing could post without it -- and every screen says it is not signed off.
CREATE TABLE finaccounting.baseline_sign_off (
    tenant_id    UUID NOT NULL,
    signer_role  VARCHAR(8) NOT NULL CHECK (signer_role IN ('FINANCE','ACTUARY')),
    signed_by    VARCHAR(100) NOT NULL,
    signed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    reference    VARCHAR(200) NOT NULL CHECK (length(trim(reference)) > 0),
    PRIMARY KEY (tenant_id, signer_role)
);

ALTER TABLE finaccounting.baseline_sign_off ENABLE ROW LEVEL SECURITY;
CREATE POLICY baseline_sign_off_tenant_isolation ON finaccounting.baseline_sign_off
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON finaccounting.baseline_sign_off TO app_role;
