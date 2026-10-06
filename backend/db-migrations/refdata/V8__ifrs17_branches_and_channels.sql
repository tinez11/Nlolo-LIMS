-- db-migrations/refdata/V8__ifrs17_branches_and_channels.sql
-- IFRS 17 I2: the controlled lists a sale is classified by (IFRS 17 spec §6). A policy's branch and sales channel
-- are posting dimensions (posting guide 2.2) and the management reports' filters, so they cannot be free text.
--
-- Global like every refdata row: one insurer's branch network today. The list is maintained here -- a new branch is
-- a new row -- and every module that accepts a branch or a channel validates it against these sets.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('BRANCH', 'DSM', 'Dar es Salaam', 'DSM', 'TZ'),
    ('BRANCH', 'ARU', 'Arusha', 'ARU', 'TZ'),
    ('BRANCH', 'MWZ', 'Mwanza', 'MWZ', 'TZ'),
    ('BRANCH', 'DOD', 'Dodoma', 'DOD', 'TZ'),
    ('BRANCH', 'ZNZ', 'Zanzibar', 'ZNZ', 'TZ'),
    ('SALES_CHANNEL', 'AGENT', 'Tied agent', 'AGENT', 'TZ'),
    ('SALES_CHANNEL', 'BROKER', 'Broker', 'BROKER', 'TZ'),
    ('SALES_CHANNEL', 'BANCASSURANCE', 'Bancassurance', 'BANCASSURANCE', 'TZ'),
    ('SALES_CHANNEL', 'DIRECT', 'Direct', 'DIRECT', 'TZ'),
    ('SALES_CHANNEL', 'DIGITAL', 'Digital', 'DIGITAL', 'TZ'),
    -- The branch a sale takes when neither its case, its agent nor the staff member who opened it names one.
    ('HEAD_OFFICE_BRANCH', 'DEFAULT', 'Branch a sale takes when nothing else names one', 'DSM', 'TZ');
