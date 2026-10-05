-- db-migrations/communication/V14__unit_linked_statement_template.sql
-- Product step 6 (U2): the policyholder is told when their yearly unit statement is filed -- the annual one only; an
-- on-demand statement is staff's and sends nothing. Platform defaults, SMS and EMAIL, Swahili and English, the same
-- shape as V13 (plan D2).
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template) VALUES
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_STATEMENT', 'SMS', 'sw',
 'Bima {{policyNumber}}: taarifa yako ya mwaka {{year}} iko tayari. Thamani ya vipande vyako ni {{closingValue}} {{currency}} kwa bei ya {{priceDate}}.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_STATEMENT', 'SMS', 'en',
 'Policy {{policyNumber}}: your {{year}} statement is ready. Your units are worth {{closingValue}} {{currency}} at the {{priceDate}} prices.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_STATEMENT', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: taarifa yako ya mwaka {{year}} iko tayari. Thamani ya vipande vyako ni {{closingValue}} {{currency}} kwa bei ya {{priceDate}}.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_STATEMENT', 'EMAIL', 'en',
 'Policy {{policyNumber}}: your {{year}} statement is ready. Your units are worth {{closingValue}} {{currency}} at the {{priceDate}} prices.');
