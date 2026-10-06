-- db-migrations/refdata/V9__journal_reason_codes.sql
-- IFRS 17 I4 (guide 2.3, user answer Q3): why a manual journal touches a BOTH account -- a reason code is required
-- there. Reference data, so finance can add a code without a release.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('JOURNAL_REASON', 'CORRECTION', 'Correction of an earlier posting', 'CORRECTION', 'TZ'),
    ('JOURNAL_REASON', 'RECLASSIFICATION', 'Reclassification between accounts', 'RECLASSIFICATION', 'TZ'),
    ('JOURNAL_REASON', 'ACCRUAL', 'Accrual', 'ACCRUAL', 'TZ'),
    ('JOURNAL_REASON', 'WRITE_OFF', 'Write-off', 'WRITE_OFF', 'TZ'),
    ('JOURNAL_REASON', 'RECONCILIATION_ADJUSTMENT', 'Reconciliation adjustment', 'RECONCILIATION_ADJUSTMENT', 'TZ'),
    ('JOURNAL_REASON', 'MIGRATION', 'Migration or opening balance', 'MIGRATION', 'TZ'),
    ('JOURNAL_REASON', 'OTHER', 'Other (explained in the reason)', 'OTHER', 'TZ');
