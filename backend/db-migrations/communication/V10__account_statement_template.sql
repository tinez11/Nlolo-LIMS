-- db-migrations/communication/V10__account_statement_template.sql
-- Product step 3: the yearly savings summary (spec §6).
--
-- One line, two figures: the balance at the year end and the interest credited during it. Both come
-- off the statement accumulation filed, which reconciled before it was filed -- so nothing here is a
-- figure the customer could not find again on the PDF.
--
-- Placeholders: policyNumber, year, balance, interest.
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template) VALUES

('00000000-0000-0000-0000-000000000000', 'ACCOUNT_STATEMENT', 'SMS', 'sw',
 'Bima {{policyNumber}}: salio la akiba tarehe 31/12/{{year}} ni {{balance}}. Riba iliyoongezwa {{year}}: {{interest}}.'),
('00000000-0000-0000-0000-000000000000', 'ACCOUNT_STATEMENT', 'SMS', 'en',
 'Policy {{policyNumber}}: your savings balance on 31/12/{{year}} is {{balance}}. Interest credited in {{year}}: {{interest}}.'),
('00000000-0000-0000-0000-000000000000', 'ACCOUNT_STATEMENT', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: salio la akiba tarehe 31/12/{{year}} ni {{balance}}. Riba iliyoongezwa {{year}}: {{interest}}. Taarifa kamili inapatikana kwa ombi.'),
('00000000-0000-0000-0000-000000000000', 'ACCOUNT_STATEMENT', 'EMAIL', 'en',
 'Policy {{policyNumber}}: your savings balance on 31/12/{{year}} is {{balance}}. Interest credited in {{year}}: {{interest}}. A full statement is available on request.');
