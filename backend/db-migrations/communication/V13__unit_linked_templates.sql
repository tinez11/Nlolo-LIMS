-- db-migrations/communication/V13__unit_linked_templates.sql
-- Product step 6 (U1): the policyholder is told when their first premium has bought units, when their fund will
-- soon not cover its charges, when the policy has lapsed because the fund could no longer meet them, and when a
-- surrender's or maturity's proceeds are on their way. Platform defaults, SMS and EMAIL, Swahili and English, the
-- same shape as V12.
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template) VALUES
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_ALLOCATED', 'SMS', 'sw',
 'Bima {{policyNumber}}: ada yako ya kwanza imenunua vipande vya mfuko vya {{allocated}} {{currency}}.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_ALLOCATED', 'SMS', 'en',
 'Policy {{policyNumber}}: your first premium has bought fund units worth {{allocated}} {{currency}}.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_ALLOCATED', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: ada yako ya kwanza imenunua vipande vya mfuko vya {{allocated}} {{currency}}.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_ALLOCATED', 'EMAIL', 'en',
 'Policy {{policyNumber}}: your first premium has bought fund units worth {{allocated}} {{currency}}.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_LOW_FUND', 'SMS', 'sw',
 'Bima {{policyNumber}}: thamani ya mfuko wako ni {{fundValue}} {{currency}}, inatosha ada za takriban miezi {{monthsCovered}}. Lipa ada yako ili kinga yako iendelee.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_LOW_FUND', 'SMS', 'en',
 'Policy {{policyNumber}}: your fund is worth {{fundValue}} {{currency}}, enough for about {{monthsCovered}} months of charges. Pay your premium to keep your cover.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_LOW_FUND', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: thamani ya mfuko wako ni {{fundValue}} {{currency}}, inatosha ada za takriban miezi {{monthsCovered}}. Lipa ada yako ili kinga yako iendelee.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_LOW_FUND', 'EMAIL', 'en',
 'Policy {{policyNumber}}: your fund is worth {{fundValue}} {{currency}}, enough for about {{monthsCovered}} months of charges. Pay your premium to keep your cover.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_LAPSED_EXHAUSTED', 'SMS', 'sw',
 'Bima {{policyNumber}}: mfuko wako haukuweza kulipia ada zake tarehe {{on}}, kwa hiyo kinga yako imesimama. Wasiliana nasi kuirejesha.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_LAPSED_EXHAUSTED', 'SMS', 'en',
 'Policy {{policyNumber}}: your fund could no longer meet its charges on {{on}}, so your cover has lapsed. Contact us to reinstate it.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_LAPSED_EXHAUSTED', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: mfuko wako haukuweza kulipia ada zake tarehe {{on}}, kwa hiyo kinga yako imesimama. Wasiliana nasi kuirejesha.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_LAPSED_EXHAUSTED', 'EMAIL', 'en',
 'Policy {{policyNumber}}: your fund could no longer meet its charges on {{on}}, so your cover has lapsed. Contact us to reinstate it.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_PROCEEDS', 'SMS', 'sw',
 'Bima {{policyNumber}}: vipande vyako vimeuzwa kwa {{amount}} {{currency}}, na malipo yanakuja.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_PROCEEDS', 'SMS', 'en',
 'Policy {{policyNumber}}: your units have been sold for {{amount}} {{currency}}, and the payment is on its way.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_PROCEEDS', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: vipande vyako vimeuzwa kwa {{amount}} {{currency}}, na malipo yanakuja.'),
('00000000-0000-0000-0000-000000000000', 'UNIT_LINKED_PROCEEDS', 'EMAIL', 'en',
 'Policy {{policyNumber}}: your units have been sold for {{amount}} {{currency}}, and the payment is on its way.');
