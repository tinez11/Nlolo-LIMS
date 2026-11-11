-- db-migrations/communication/V21__agent_cash_names_the_agent.sql
-- The agent-cash message names the agent who took the money (2026-11-11, the user: "your agent Juma Senior"): a
-- customer who did not hand cash to that person knows at once something is wrong. The cash now pays the premium the
-- moment the agent records it, so the message says so; the numbered receipt still follows when the money reaches us.
-- Placeholders: agentName, amount, policyNumber.
-- Only the platform's default rows (the zero tenant); a tenant's own wording is theirs to change.
-- Re-runnable: a second run changes nothing.

UPDATE communication.notification_template SET body_template =
    'Wakala wako {{agentName}} amepokea taslimu {{amount}} kwa bima {{policyNumber}}. Malipo yako yamepokelewa; risiti itafuata pesa zikitufikia.'
 WHERE tenant_id = '00000000-0000-0000-0000-000000000000' AND template_key = 'AGENT_CASH_RECEIVED'
   AND channel = 'SMS' AND language = 'sw';
UPDATE communication.notification_template SET body_template =
    'Your agent {{agentName}} has collected {{amount}} in cash for policy {{policyNumber}}. Your payment is recorded; your receipt will follow once the money reaches us.'
 WHERE tenant_id = '00000000-0000-0000-0000-000000000000' AND template_key = 'AGENT_CASH_RECEIVED'
   AND channel = 'SMS' AND language = 'en';

-- The email the message never had (2026-11-11, found walking the flow on dev): a customer with an email address got
-- "No EMAIL/sw template seeded for AGENT_CASH_RECEIVED". The same words, with what to keep.
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template)
SELECT * FROM (VALUES
  ('00000000-0000-0000-0000-000000000000'::uuid, 'AGENT_CASH_RECEIVED', 'EMAIL', 'sw',
   'Wakala wako {{agentName}} amepokea taslimu {{amount}} kwa bima {{policyNumber}}. Malipo yako yamepokelewa; risiti itafuata pesa zikitufikia. Kama hukumkabidhi {{agentName}} pesa hizi, wasiliana nasi mara moja.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'AGENT_CASH_RECEIVED', 'EMAIL', 'en',
   'Your agent {{agentName}} has collected {{amount}} in cash for policy {{policyNumber}}. Your payment is recorded; your receipt will follow once the money reaches us. If you did not hand this money to {{agentName}}, contact us at once.')
) AS t(tenant_id, template_key, channel, language, body_template)
WHERE NOT EXISTS (
  SELECT 1 FROM communication.notification_template n
   WHERE n.tenant_id = t.tenant_id AND n.template_key = t.template_key AND n.channel = t.channel
     AND n.language = t.language);
