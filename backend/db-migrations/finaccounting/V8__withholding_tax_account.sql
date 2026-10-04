-- db-migrations/finaccounting/V8__withholding_tax_account.sql
-- Product step 5 (D1): 2230 Withholding Tax Payable, under 2200 Payables -- what a payout's withheld
-- tax is owed to the tax authority until finance remits it.
--
-- For every tenant whose chart is ALREADY seeded: ChartOfAccountBlueprint seeds new tenants, and
-- seedIfAbsent skips a tenant that has any account, so an existing chart would otherwise never get
-- it. Same derived type and balance as V5; idempotent.
INSERT INTO finaccounting.chart_of_account
    (tenant_id, account_code, name, account_type, normal_balance,
     parent_code, level, posting_allowed, status, currency, control_of, created_by)
SELECT DISTINCT c.tenant_id, '2230', 'Withholding Tax Payable', 'LIABILITY', 'CR',
       '2200', 3::smallint, TRUE, 'ACTIVE', 'TZS', NULL, 'migration:finaccounting/V8'
FROM finaccounting.chart_of_account c
WHERE c.account_code = '2200'
ON CONFLICT (tenant_id, account_code) DO NOTHING;
