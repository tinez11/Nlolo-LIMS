-- db-migrations/finaccounting/V9__unit_linked_accounts.sql
-- Product step 6 (U1): three accounts for unit-linked business, for every tenant whose chart is ALREADY seeded
-- (ChartOfAccountBlueprint seeds new tenants; seedIfAbsent skips a tenant that has any account). V8's shape.
--   2150 Unit-Linked Policyholder Liability -- what is owed in units; always units in issue x current price
--   4310 Unit-Linked Charges Income         -- the allocation charge, the policy fee and the cost of insurance
--   5600 Change in Unit-Linked Liability    -- price movements on the units held
-- Idempotent.
INSERT INTO finaccounting.chart_of_account
    (tenant_id, account_code, name, account_type, normal_balance,
     parent_code, level, posting_allowed, status, currency, control_of, created_by)
SELECT DISTINCT c.tenant_id, '2150', 'Unit-Linked Policyholder Liability', 'LIABILITY', 'CR',
       '2100', 3::smallint, TRUE, 'ACTIVE', 'TZS', NULL, 'migration:finaccounting/V9'
FROM finaccounting.chart_of_account c
WHERE c.account_code = '2100'
ON CONFLICT (tenant_id, account_code) DO NOTHING;

INSERT INTO finaccounting.chart_of_account
    (tenant_id, account_code, name, account_type, normal_balance,
     parent_code, level, posting_allowed, status, currency, control_of, created_by)
SELECT DISTINCT c.tenant_id, '4310', 'Unit-Linked Charges Income', 'INCOME', 'CR',
       '4000', 2::smallint, TRUE, 'ACTIVE', 'TZS', NULL, 'migration:finaccounting/V9'
FROM finaccounting.chart_of_account c
WHERE c.account_code = '4000'
ON CONFLICT (tenant_id, account_code) DO NOTHING;

INSERT INTO finaccounting.chart_of_account
    (tenant_id, account_code, name, account_type, normal_balance,
     parent_code, level, posting_allowed, status, currency, control_of, created_by)
SELECT DISTINCT c.tenant_id, '5600', 'Change in Unit-Linked Liability', 'EXPENSE', 'DR',
       '5000', 2::smallint, TRUE, 'ACTIVE', 'TZS', NULL, 'migration:finaccounting/V9'
FROM finaccounting.chart_of_account c
WHERE c.account_code = '5000'
ON CONFLICT (tenant_id, account_code) DO NOTHING;
