-- db-migrations/communication/V22__premium_reminder_template.sql
-- The reminder staff or the customer's agent send from Premiums to follow up (2026-11-11, analytics plan 1). Wording
-- approved by the user 2026-11-11. These are the platform's default rows (the zero tenant); a company that edits the
-- message in Message templates gets its own copy (copy-on-write), as every template already works.
-- Placeholders: amount, policyNumber, dueDate (dd/MM/yyyy).
-- Re-runnable: a second run changes nothing.

INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template)
SELECT * FROM (VALUES
  ('00000000-0000-0000-0000-000000000000'::uuid, 'PREMIUM_REMINDER', 'SMS', 'en',
   'Reminder: the premium of {{amount}} for policy {{policyNumber}} was due on {{dueDate}}. Please pay to keep your cover.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'PREMIUM_REMINDER', 'SMS', 'sw',
   'Kumbusho: ada ya {{amount}} kwa bima {{policyNumber}} ilitakiwa kulipwa {{dueDate}}. Tafadhali lipa ili bima yako iendelee.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'PREMIUM_REMINDER', 'EMAIL', 'en',
   'Reminder: the premium of {{amount}} for policy {{policyNumber}} was due on {{dueDate}}. Please pay to keep your cover. If you have already paid, thank you — please ignore this message.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'PREMIUM_REMINDER', 'EMAIL', 'sw',
   'Kumbusho: ada ya {{amount}} kwa bima {{policyNumber}} ilitakiwa kulipwa {{dueDate}}. Tafadhali lipa ili bima yako iendelee. Kama umeshalipa, asante — tafadhali puuza ujumbe huu.')
) AS t(tenant_id, template_key, channel, language, body_template)
WHERE NOT EXISTS (
  SELECT 1 FROM communication.notification_template n
   WHERE n.tenant_id = t.tenant_id AND n.template_key = t.template_key AND n.channel = t.channel
     AND n.language = t.language);
