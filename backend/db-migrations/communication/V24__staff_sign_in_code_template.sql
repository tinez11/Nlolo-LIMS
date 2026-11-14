-- db-migrations/communication/V24__staff_sign_in_code_template.sql
-- 2026-11-14, staff sign-in code: the text carrying a staff member's sign-in code. Editable like any template (the user:
-- "yes but editable on template"); the sender falls back to its own English wording if an edit drops {{code}}.
-- Swahili is a DRAFT for the user to check. Placeholder: code.
-- The platform's default rows (the zero tenant); a company that edits the message gets its own copy. Re-runnable.

INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template)
SELECT * FROM (VALUES
  ('00000000-0000-0000-0000-000000000000'::uuid, 'STAFF_SIGN_IN_CODE', 'SMS', 'en',
   'Your Nlolo sign-in code is {{code}}. It expires in 5 minutes. Never share it.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'STAFF_SIGN_IN_CODE', 'SMS', 'sw',
   'Namba yako ya kuingia Nlolo ni {{code}}. Itaisha baada ya dakika 5. Usimpe mtu yeyote.')
) AS t(tenant_id, template_key, channel, language, body_template)
WHERE NOT EXISTS (
  SELECT 1 FROM communication.notification_template n
   WHERE n.tenant_id = t.tenant_id AND n.template_key = t.template_key AND n.channel = t.channel
     AND n.language = t.language);
