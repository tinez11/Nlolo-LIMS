-- db-migrations/communication/V17__agent_cash_received_template.sql
-- Cash handed to an agent is acknowledged the moment the agent records it (receipts step 1, 2026-11-05; TIRA
-- Insurance Regulations 2009, Code B.5(b): an intermediary shall "acknowledge receipt ... of all monies received").
-- The customer holds something from the moment the cash leaves their hand; the numbered receipt follows when the
-- money reaches the insurer and is confirmed (PAYMENT_RECEIVED).
-- Placeholders: policyNumber, amount.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template)
SELECT * FROM (VALUES
  ('00000000-0000-0000-0000-000000000000'::uuid, 'AGENT_CASH_RECEIVED', 'SMS', 'sw',
   'Wakala wako amepokea taslimu {{amount}} kwa bima {{policyNumber}}. Risiti yako itafuata pesa zikitufikia.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'AGENT_CASH_RECEIVED', 'SMS', 'en',
   'Your agent has collected {{amount}} in cash for policy {{policyNumber}}. Your receipt will follow once the money reaches us.')
) AS t(tenant_id, template_key, channel, language, body_template)
WHERE NOT EXISTS (
  SELECT 1 FROM communication.notification_template n
   WHERE n.tenant_id = t.tenant_id AND n.template_key = t.template_key AND n.channel = t.channel
     AND n.language = t.language);
