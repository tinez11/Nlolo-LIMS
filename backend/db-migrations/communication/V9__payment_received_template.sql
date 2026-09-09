-- db-migrations/communication/V9__payment_received_template.sql
-- A receipt, for every premium after the first.
--
-- The four offer-lifecycle messages cover a policy up to the moment cover starts and no further:
-- offer made, offer closing, cover started, offer expired. A customer paying their SECOND month
-- got nothing back at all -- no acknowledgement that the money arrived, no record they could
-- point to. That is not a bug in the offer flow, it is a hole beside it: activateOnFirstPremium
-- is silent on an already-ACTIVE policy on purpose, because re-publishing PolicyActivated would
-- double-accrue the agent's commission and double-cede the risk.
--
-- So the receipt is its own message on its own listener, deliberately NOT folded into activation.
-- It fires on every collected premium including the first, which means a customer starting cover
-- receives both "payment received" and "you are covered" -- two true and different facts, and the
-- second is the one that matters to them.
--
-- Placeholders: policyNumber, amount. Deliberately no running balance or next-due date: billing
-- knows both, this module does not, and inventing them from an event payload would put figures in
-- front of a customer that nothing reconciles.
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template) VALUES

('00000000-0000-0000-0000-000000000000', 'PAYMENT_RECEIVED', 'SMS', 'sw',
 'Tumepokea malipo ya {{amount}} kwa bima {{policyNumber}}. Asante.'),
('00000000-0000-0000-0000-000000000000', 'PAYMENT_RECEIVED', 'SMS', 'en',
 'We have received your payment of {{amount}} for policy {{policyNumber}}. Thank you.'),
('00000000-0000-0000-0000-000000000000', 'PAYMENT_RECEIVED', 'EMAIL', 'sw',
 'Tumepokea malipo ya {{amount}} kwa bima {{policyNumber}}. Asante. Hifadhi ujumbe huu kama uthibitisho wa malipo yako.'),
('00000000-0000-0000-0000-000000000000', 'PAYMENT_RECEIVED', 'EMAIL', 'en',
 'We have received your payment of {{amount}} for policy {{policyNumber}}. Thank you. Please keep this message as confirmation of your payment.');
