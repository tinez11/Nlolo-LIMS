-- 2026-11-14, shareholders register: the reason code an approved share issue carries on bank 1110 (a BOTH account
-- needs one, finaccounting V10's LEDGER_MODE guard).
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('JOURNAL_REASON', 'SHARE_CAPITAL', 'Shares issued for cash', 'SHARE_CAPITAL', 'TZ')
ON CONFLICT DO NOTHING;
