-- db-migrations/communication/V23__maturity_payout_redirected_template.sql
-- The client is told when a deposit's maturity payment is pointed at a number or account (2026-11-12, finance finding
-- 146: one person could redirect a maturity payout to any number, at once, with no word to the client). Sent to their
-- number on record, so a redirect they did not ask for is caught before the money moves.
-- Wording: DRAFT for the user's approval. Placeholders: policyNumber, maturityDate (dd/MM/yyyy), last3.
-- The platform's default rows (the zero tenant); a company that edits the message gets its own copy.
-- Re-runnable: a second run changes nothing.

INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template)
SELECT * FROM (VALUES
  ('00000000-0000-0000-0000-000000000000'::uuid, 'MATURITY_PAYOUT_REDIRECTED', 'SMS', 'en',
   'Policy {{policyNumber}}: your deposit paid on {{maturityDate}} will go to the number or account ending {{last3}}. If you did not ask for this, contact us at once.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'MATURITY_PAYOUT_REDIRECTED', 'SMS', 'sw',
   'Bima {{policyNumber}}: amana yako itakayolipwa {{maturityDate}} itatumwa kwenye namba au akaunti inayoishia {{last3}}. Kama hukuomba hivi, wasiliana nasi mara moja.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'MATURITY_PAYOUT_REDIRECTED', 'EMAIL', 'en',
   'Policy {{policyNumber}}: your deposit paid on {{maturityDate}} will go to the number or account ending {{last3}}. If you did not ask for this, contact us at once.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'MATURITY_PAYOUT_REDIRECTED', 'EMAIL', 'sw',
   'Bima {{policyNumber}}: amana yako itakayolipwa {{maturityDate}} itatumwa kwenye namba au akaunti inayoishia {{last3}}. Kama hukuomba hivi, wasiliana nasi mara moja.')
) AS t(tenant_id, template_key, channel, language, body_template)
WHERE NOT EXISTS (
  SELECT 1 FROM communication.notification_template n
   WHERE n.tenant_id = t.tenant_id AND n.template_key = t.template_key AND n.channel = t.channel
     AND n.language = t.language);
