-- db-migrations/communication/V12__funeral_templates.sql
-- Family funeral cover: the main member is told when a life joins, when a life's cover ends (never for a
-- death -- the family is already in a claim), and when the premium changes. Platform defaults, SMS and
-- EMAIL, Swahili and English, the same shape as V11.
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template) VALUES
('00000000-0000-0000-0000-000000000000', 'FUNERAL_LIFE_ADDED', 'SMS', 'sw',
 'Bima {{policyNumber}}: {{fullName}} amelindwa kuanzia tarehe {{coverStart}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_LIFE_ADDED', 'SMS', 'en',
 'Policy {{policyNumber}}: {{fullName}} is covered from {{coverStart}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_LIFE_ADDED', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: {{fullName}} amelindwa kuanzia tarehe {{coverStart}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_LIFE_ADDED', 'EMAIL', 'en',
 'Policy {{policyNumber}}: {{fullName}} is covered from {{coverStart}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_LIFE_ENDED', 'SMS', 'sw',
 'Bima {{policyNumber}}: kinga ya {{fullName}} imeisha tarehe {{endedOn}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_LIFE_ENDED', 'SMS', 'en',
 'Policy {{policyNumber}}: cover for {{fullName}} ended on {{endedOn}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_LIFE_ENDED', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: kinga ya {{fullName}} imeisha tarehe {{endedOn}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_LIFE_ENDED', 'EMAIL', 'en',
 'Policy {{policyNumber}}: cover for {{fullName}} ended on {{endedOn}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_PREMIUM_CHANGED', 'SMS', 'sw',
 'Bima {{policyNumber}}: ada yako ni {{amount}} {{currency}} kuanzia tarehe {{effectiveFrom}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_PREMIUM_CHANGED', 'SMS', 'en',
 'Policy {{policyNumber}}: your premium is {{amount}} {{currency}} from {{effectiveFrom}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_PREMIUM_CHANGED', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: ada yako ni {{amount}} {{currency}} kuanzia tarehe {{effectiveFrom}}.'),
('00000000-0000-0000-0000-000000000000', 'FUNERAL_PREMIUM_CHANGED', 'EMAIL', 'en',
 'Policy {{policyNumber}}: your premium is {{amount}} {{currency}} from {{effectiveFrom}}.');
