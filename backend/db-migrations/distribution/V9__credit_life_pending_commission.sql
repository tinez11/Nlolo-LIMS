-- A lender's commission on a credit-life file waits for the file's premium (2026-10-30, the user: the bank pays the
-- full premium, then its commission is sent). Booked at acceptance before this, so the month-end close could make it
-- payable on a file the lender never paid; and every file's invoice after the first then earned RENEWAL as well,
-- paying the lender twice.
--
-- credit_life_file_commission: what one accepted file will earn its lender, held until billing says that file's invoice
--   is PAID; released_accrual_id is the accrual it became.
-- credit_life_early_refund: a borrower whose loan ended before the lender paid. Nothing was booked yet to claw back
--   from, so the refund is remembered and reversed from the commission the moment it is booked.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

CREATE TABLE IF NOT EXISTS distribution.credit_life_file_commission (
    submission_id        UUID          PRIMARY KEY,
    tenant_id            UUID          NOT NULL,
    policy_number        VARCHAR(30)   NOT NULL,
    agent_id             UUID          NOT NULL,
    amount               NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency             CHAR(3)       NOT NULL,
    file_premium         NUMERIC(19,2) NOT NULL CHECK (file_premium > 0),
    created_at           TIMESTAMPTZ   NOT NULL,
    released_accrual_id  UUID,
    released_at          TIMESTAMPTZ,
    CONSTRAINT chk_cl_file_commission_released CHECK ((released_accrual_id IS NULL) = (released_at IS NULL))
);
CREATE INDEX IF NOT EXISTS ix_cl_file_commission_policy
    ON distribution.credit_life_file_commission (tenant_id, policy_number);

CREATE TABLE IF NOT EXISTS distribution.credit_life_early_refund (
    tenant_id         UUID          NOT NULL,
    submission_id     UUID          NOT NULL REFERENCES distribution.credit_life_file_commission (submission_id),
    policy_member_id  UUID          NOT NULL,
    refunded          NUMERIC(19,2) NOT NULL CHECK (refunded > 0),
    created_at        TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (submission_id, policy_member_id)
);

ALTER TABLE distribution.credit_life_file_commission ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS credit_life_file_commission_tenant_isolation ON distribution.credit_life_file_commission;
CREATE POLICY credit_life_file_commission_tenant_isolation ON distribution.credit_life_file_commission
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE distribution.credit_life_early_refund ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS credit_life_early_refund_tenant_isolation ON distribution.credit_life_early_refund;
CREATE POLICY credit_life_early_refund_tenant_isolation ON distribution.credit_life_early_refund
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON distribution.credit_life_file_commission TO app_role;
GRANT SELECT, INSERT ON distribution.credit_life_early_refund TO app_role;
