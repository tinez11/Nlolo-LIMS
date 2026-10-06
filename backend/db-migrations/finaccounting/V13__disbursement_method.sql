-- db-migrations/finaccounting/V13__disbursement_method.sql
-- IFRS 17 I3b (user answer Q3): money out leaves through the account of the rail that paid it -- a mobile money wallet
-- (1140) or the claims and benefits bank account (1130, a bank transfer). The paying modules' own "paid" events do not
-- say which; payment.DisbursementCompleted does. finaccounting records it per payment source reference (the claim,
-- surrender request, instalment, statement or loan id -- the same reference the paid event posts under) before those
-- modules react, and posting reads it back.

CREATE TABLE finaccounting.disbursement_method (
    tenant_id     UUID NOT NULL,
    purpose       VARCHAR(30) NOT NULL,
    source_ref    VARCHAR(100) NOT NULL,
    method        VARCHAR(20) NOT NULL CHECK (method IN ('MOBILE_MONEY','EFT')),
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, purpose, source_ref)
);
CREATE INDEX idx_disbursement_method_ref ON finaccounting.disbursement_method (tenant_id, source_ref);
ALTER TABLE finaccounting.disbursement_method ENABLE ROW LEVEL SECURITY;
CREATE POLICY disbursement_method_tenant_isolation ON finaccounting.disbursement_method
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON finaccounting.disbursement_method TO app_role;
