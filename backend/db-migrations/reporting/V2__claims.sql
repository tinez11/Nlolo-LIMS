-- db-migrations/reporting/V2__claims.sql
-- Claims for the variance reports (2026-11-09, the reports redesign batch 2): one row per claim, filled from the
-- claims.* events and rebuildable by replaying the audit journal like every other reporting table. Per-claim facts,
-- not totals, so any span of months and any comparison can be asked of it.
--
-- Claims incurred are claims APPROVED in a period, at their approved amount: a claim carries no amount until it is
-- approved. payable_amount is what the settlement asked for after netting (an outstanding loan, unpaid premium);
-- paid_amount is what was actually paid.

CREATE TABLE IF NOT EXISTS reporting.report_claim (
    tenant_id               UUID          NOT NULL,
    claim_id                UUID          NOT NULL,
    policy_number           VARCHAR(30),
    claim_type              VARCHAR(30),
    date_of_event           DATE,
    registered_on           DATE,
    approved_on             DATE,
    approved_amount         NUMERIC(19,2),
    rejected_on             DATE,
    reject_reason           VARCHAR(500),
    settlement_requested_on DATE,
    payable_amount          NUMERIC(19,2),
    settled_on              DATE,
    paid_amount             NUMERIC(19,2),
    -- The last time a declined or paid claim was reopened (claims.ClaimReopened): open again from that day.
    reopened_on             DATE,
    currency                CHAR(3)       NOT NULL DEFAULT 'TZS',
    PRIMARY KEY (tenant_id, claim_id)
);

CREATE INDEX IF NOT EXISTS ix_report_claim_registered ON reporting.report_claim (tenant_id, registered_on);
CREATE INDEX IF NOT EXISTS ix_report_claim_approved ON reporting.report_claim (tenant_id, approved_on);
CREATE INDEX IF NOT EXISTS ix_report_claim_policy ON reporting.report_claim (tenant_id, policy_number);

ALTER TABLE reporting.report_claim ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS report_claim_tenant_isolation ON reporting.report_claim;
CREATE POLICY report_claim_tenant_isolation ON reporting.report_claim
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.report_claim TO app_role;
