-- db-migrations/policyloan/V8__interest_month_published.sql
-- IFRS 17 I3b (posting guide E-02): each completed month's accrued loan interest is published once, as
-- policyloan.LoanInterestAccrued, so the ledger accrues it (Dr 2126 / Cr 2121, LN_INT). Interest itself is accrued
-- daily by accrue_loan_interest() (pg_cron) into loan_transaction; this records which (loan, month) has been told.

CREATE TABLE policyloan.interest_month_published (
    tenant_id     UUID NOT NULL,
    loan_id       UUID NOT NULL REFERENCES policyloan.policy_loan (loan_id),
    period        VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    amount        NUMERIC(19,2) NOT NULL,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, loan_id, period)
);
ALTER TABLE policyloan.interest_month_published ENABLE ROW LEVEL SECURITY;
CREATE POLICY interest_month_published_tenant_isolation ON policyloan.interest_month_published
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON policyloan.interest_month_published TO app_role;

-- Each (loan, month) before p_month_start (the first day of the current month in Dar es Salaam) with interest accrued
-- and not yet published, across tenants: the drain runs under no tenant, and publishes each under its own.
CREATE OR REPLACE FUNCTION policyloan.loan_interest_months_to_publish(p_month_start DATE)
RETURNS TABLE (tenant_id UUID, loan_id UUID, policy_number VARCHAR, period TEXT, amount NUMERIC, currency CHAR(3))
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT t.tenant_id, t.loan_id, l.policy_number,
           to_char(t.occurred_at AT TIME ZONE 'Africa/Dar_es_Salaam', 'YYYY-MM') AS period,
           sum(t.amount), min(t.currency)
      FROM policyloan.loan_transaction t
      JOIN policyloan.policy_loan l ON l.loan_id = t.loan_id
     WHERE t.transaction_type = 'INTEREST_ACCRUAL'
       AND t.occurred_at < (p_month_start::timestamp AT TIME ZONE 'Africa/Dar_es_Salaam')
       AND NOT EXISTS (SELECT 1 FROM policyloan.interest_month_published p
                        WHERE p.tenant_id = t.tenant_id AND p.loan_id = t.loan_id
                          AND p.period = to_char(t.occurred_at AT TIME ZONE 'Africa/Dar_es_Salaam', 'YYYY-MM'))
     GROUP BY t.tenant_id, t.loan_id, l.policy_number, 4
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION policyloan.loan_interest_months_to_publish(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION policyloan.loan_interest_months_to_publish(DATE) TO app_role;
