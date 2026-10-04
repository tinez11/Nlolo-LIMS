-- db-migrations/communication/V11__vesting_reminder_template.sql
-- Product step 5 D2: a pension's holder is told 90 and 30 days before it vests, so they can choose
-- how it is paid before it vests into the version's default with no lump sum.
--
-- Placeholders: policyNumber, vestingDate.
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template) VALUES

('00000000-0000-0000-0000-000000000000', 'PENSION_VESTING_REMINDER', 'SMS', 'sw',
 'Bima {{policyNumber}}: pensheni yako itaanza tarehe {{vestingDate}}. Wasiliana nasi kuchagua jinsi itakavyolipwa.'),
('00000000-0000-0000-0000-000000000000', 'PENSION_VESTING_REMINDER', 'SMS', 'en',
 'Policy {{policyNumber}}: your pension starts on {{vestingDate}}. Contact us to choose how it is paid.'),
('00000000-0000-0000-0000-000000000000', 'PENSION_VESTING_REMINDER', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: pensheni yako itaanza tarehe {{vestingDate}}. Wasiliana nasi kuchagua jinsi itakavyolipwa.'),
('00000000-0000-0000-0000-000000000000', 'PENSION_VESTING_REMINDER', 'EMAIL', 'en',
 'Policy {{policyNumber}}: your pension starts on {{vestingDate}}. Contact us to choose how it is paid.');
