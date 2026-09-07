-- Module: finaccounting V5 -- the chart of accounts becomes a real hierarchy.
--
-- Design: docs/superpowers/specs/2026-09-05-chart-of-accounts-design.md
--
-- EVERY ACCOUNT BELOW IS A PLACEHOLDER pending FINANCE sign-off, exactly as the
-- nine it replaces were. No document on this platform specifies account codes or
-- their debit/credit treatment.
--
-- WHY THE FOREIGN KEY COMES DOWN. Eight of the nine legacy codes are reused in
-- this chart with a DIFFERENT meaning, and three of them rotate:
--   5000 Claims Expense            -> 5100
--   5100 Commission Expense        -> 5200
--   5200 Reinsurance Ceded Premium -> 5500
-- There is therefore no ordering of plain INSERTs and DELETEs that avoids a
-- primary-key collision on (tenant_id, account_code), and no ordering of UPDATEs
-- that avoids violating fk_gl_posting_account_code. The constraint is dropped for
-- the duration of the remap and recreated at the end -- and its successful
-- recreation is itself the assertion that every posting landed on a code that
-- exists. An orphan makes this migration FAIL rather than pass quietly.
--
-- Four codes also stop being postable: 1000, 1200, 2200 and 5000 currently
-- receive postings and become non-posting parent headers here. Their postings
-- move to real leaves (1120, 1210, 2140, 5100) in step 4.
--
-- Every statement is generic over tenant_id. Nothing is hardcoded to a tenant,
-- for the reason V2 section 5 gave when it kept the seed INSERT out of SQL.
--
-- V1-V4 are immutable (already applied and reviewed), which is why this arrives
-- as a fifth migration rather than an edit to V2's section 5.

-- =============================================================================
-- 1. The new columns.
--
--    The defaults keep the nine legacy rows valid for the moment they remain --
--    steps 5-6 replace those rows outright, so the defaults are scaffolding, not
--    the intended values for anything that survives this file.
-- =============================================================================
ALTER TABLE finaccounting.chart_of_account ADD COLUMN parent_code VARCHAR(20);
ALTER TABLE finaccounting.chart_of_account ADD COLUMN level SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE finaccounting.chart_of_account ADD COLUMN posting_allowed BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE finaccounting.chart_of_account ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','INACTIVE'));
ALTER TABLE finaccounting.chart_of_account ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'TZS';
ALTER TABLE finaccounting.chart_of_account ADD COLUMN control_of VARCHAR(20);
ALTER TABLE finaccounting.chart_of_account ADD COLUMN description TEXT;

-- Composite and tenant-first, for the same reason fk_gl_posting_account_code is
-- (finaccounting/V3): each tenant owns its own chart, so the constraint must
-- never let tenant A's account be the parent of tenant B's. Referential-integrity
-- checks bypass row-level security by design in PostgreSQL, so the tenant_id
-- column in the key -- not RLS -- is what keeps this tenant-safe.
ALTER TABLE finaccounting.chart_of_account
    ADD CONSTRAINT fk_chart_of_account_parent
    FOREIGN KEY (tenant_id, parent_code)
    REFERENCES finaccounting.chart_of_account (tenant_id, account_code);

CREATE INDEX idx_chart_of_account_parent
    ON finaccounting.chart_of_account (tenant_id, parent_code);

-- =============================================================================
-- 2. Remember which tenants have a chart, BEFORE step 4 empties the table.
--
--    Step 5 needs this list, and it cannot re-derive it from chart_of_account:
--    by then every row in that table has been deleted, so a DISTINCT over it
--    returns nothing and the new chart is silently inserted for no one -- leaving
--    every remapped posting pointing at a code that does not exist. Deriving it
--    from gl_posting instead would be wrong in the other direction: a tenant that
--    has a chart but has never posted would lose its chart entirely.
-- =============================================================================
CREATE TEMP TABLE v5_charted_tenants AS
SELECT DISTINCT tenant_id FROM finaccounting.chart_of_account;

-- =============================================================================
-- 3. Release the ledger.
--
--    gl_posting is PARTITION BY RANGE (created_at). An outgoing foreign key on a
--    partitioned table is dropped once, for every partition (PostgreSQL 12+),
--    exactly as V3 noted when it added one -- no per-partition step is needed
--    here and none should be added.
-- =============================================================================
ALTER TABLE finaccounting.gl_posting DROP CONSTRAINT fk_gl_posting_account_code;

-- =============================================================================
-- 4. Remap every existing posting.
--
--    The rotation is safe here precisely because nothing constrains the column
--    right now, and because a single UPDATE evaluates its FROM against the
--    PRE-update snapshot and visits each target row at most once -- so
--    5100 -> 5200 cannot cascade onto 5200 -> 5500. Each old_code appears exactly
--    once in the VALUES list, so no row can match two mappings.
-- =============================================================================
UPDATE finaccounting.gl_posting AS p
SET account_code = m.new_code
FROM (VALUES
    ('1000','1120'), ('1200','1210'), ('1300','1240'), ('1400','1250'),
    ('2200','2140'), ('2300','2220'), ('5000','5100'), ('5100','5200'),
    ('5200','5500')
) AS m(old_code, new_code)
WHERE p.account_code = m.old_code;

-- =============================================================================
-- 5. The legacy chart rows are now unreferenced.
-- =============================================================================
DELETE FROM finaccounting.chart_of_account
WHERE account_code IN ('1000','1200','1300','1400','2200','2300','5000','5100','5200');

-- =============================================================================
-- 6. Insert the 36-account chart for every tenant that had one.
--
--    ORDER BY level, so parents land before children and
--    fk_chart_of_account_parent holds at every step of the INSERT.
--
--    Mirrors ChartOfAccountBlueprint.accounts() exactly, because a migration
--    cannot call Java; ChartOfAccountMigrationV5Test asserts the two agree so
--    they cannot drift.
--
--    account_type/normal_balance stay DERIVED from the leading digit, computed
--    here the same way PostingRule.accountTypeFor/normalBalanceFor do it, so no
--    stored value can ever contradict the code.
--
--    The 4xxx income accounts are seeded and will stay at zero: premium is EARNED
--    through LRC release, which is C1-blocked (Actuarial), and no posting rule
--    targets them. Chart completeness, not IFRS 17 measurement.
-- =============================================================================
INSERT INTO finaccounting.chart_of_account
    (tenant_id, account_code, name, account_type, normal_balance,
     parent_code, level, posting_allowed, status, currency, control_of, created_by)
SELECT t.tenant_id, a.code, a.name,
       CASE left(a.code, 1)
           WHEN '1' THEN 'ASSET' WHEN '2' THEN 'LIABILITY' WHEN '3' THEN 'EQUITY'
           WHEN '4' THEN 'INCOME' ELSE 'EXPENSE' END,
       CASE WHEN left(a.code, 1) IN ('2','3','4') THEN 'CR' ELSE 'DR' END,
       a.parent_code, a.level, a.posting_allowed, 'ACTIVE', 'TZS', a.control_of,
       'migration:finaccounting/V5'
FROM v5_charted_tenants AS t
CROSS JOIN (VALUES
    ('1000','Assets',                        NULL,   1::smallint, FALSE, NULL),
    ('1100','Cash and Cash Equivalents',     '1000', 2::smallint, FALSE, NULL),
    ('1110','Main Bank Account',             '1100', 3::smallint, TRUE,  NULL),
    ('1120','Mobile Money',                  '1100', 3::smallint, TRUE,  NULL),
    ('1130','Petty Cash',                    '1100', 3::smallint, TRUE,  NULL),
    ('1200','Receivables',                   '1000', 2::smallint, FALSE, NULL),
    ('1210','Premium Receivables',           '1200', 3::smallint, TRUE,  'BILLING'),
    ('1220','Agent Receivables',             '1200', 3::smallint, TRUE,  'DISTRIBUTION'),
    ('1230','Other Receivables',             '1200', 3::smallint, TRUE,  NULL),
    ('1240','Reinsurance Recoverable',       '1200', 3::smallint, TRUE,  'REINSURANCE'),
    ('1250','Policy Loan Receivables',       '1200', 3::smallint, TRUE,  'POLICYLOAN'),
    ('1300','Investments',                   '1000', 2::smallint, TRUE,  NULL),
    ('2000','Liabilities',                   NULL,   1::smallint, FALSE, NULL),
    ('2100','Insurance Liabilities',         '2000', 2::smallint, FALSE, NULL),
    ('2110','Claims Payable',                '2100', 3::smallint, TRUE,  'CLAIMS'),
    ('2120','Premiums Received in Advance',  '2100', 3::smallint, TRUE,  NULL),
    ('2130','Policyholder Benefits Payable', '2100', 3::smallint, TRUE,  NULL),
    ('2140','Unearned Premium',              '2100', 3::smallint, TRUE,  NULL),
    ('2200','Payables',                      '2000', 2::smallint, FALSE, NULL),
    ('2210','Agent Commissions Payable',     '2200', 3::smallint, TRUE,  'DISTRIBUTION'),
    ('2220','Reinsurance Payable',           '2200', 3::smallint, TRUE,  'REINSURANCE'),
    ('2300','Other Liabilities',             '2000', 2::smallint, TRUE,  NULL),
    ('3000','Equity',                        NULL,   1::smallint, FALSE, NULL),
    ('3100','Share Capital',                 '3000', 2::smallint, TRUE,  NULL),
    ('3200','Retained Earnings',             '3000', 2::smallint, TRUE,  NULL),
    ('3300','Current Year Profit/Loss',      '3000', 2::smallint, TRUE,  NULL),
    ('4000','Income',                        NULL,   1::smallint, FALSE, NULL),
    ('4100','Premium Income',                '4000', 2::smallint, TRUE,  NULL),
    ('4200','Investment Income',             '4000', 2::smallint, TRUE,  NULL),
    ('4300','Other Income',                  '4000', 2::smallint, TRUE,  NULL),
    ('5000','Expenses',                      NULL,   1::smallint, FALSE, NULL),
    ('5100','Claims Expense',                '5000', 2::smallint, TRUE,  NULL),
    ('5200','Commission Expense',            '5000', 2::smallint, TRUE,  NULL),
    ('5300','Operating Expenses',            '5000', 2::smallint, TRUE,  NULL),
    ('5400','Other Expenses',                '5000', 2::smallint, TRUE,  NULL),
    ('5500','Reinsurance Ceded Premium',     '5000', 2::smallint, TRUE,  NULL)
) AS a(code, name, parent_code, level, posting_allowed, control_of)
ORDER BY a.level, a.code;

-- =============================================================================
-- 7. Re-arm the ledger.
--
--    This statement FAILS if step 3 left any posting naming a code that does not
--    exist -- which is the assertion, not merely the repair. Same composite shape
--    V3 created it with; ON DELETE stays at the default NO ACTION deliberately,
--    so a posted-against account still cannot be deleted.
-- =============================================================================
ALTER TABLE finaccounting.gl_posting
    ADD CONSTRAINT fk_gl_posting_account_code
    FOREIGN KEY (tenant_id, account_code)
    REFERENCES finaccounting.chart_of_account (tenant_id, account_code);
