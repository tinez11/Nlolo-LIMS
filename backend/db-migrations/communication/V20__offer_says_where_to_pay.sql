-- db-migrations/communication/V20__offer_says_where_to_pay.sql
-- The offer told the customer how much and by when, but not WHERE to pay (2026-11-08, phase 3 of the public quote
-- link). Most insurers here take the first premium to a mobile-money business number with the policy number as the
-- reference, and match it on their side; no payment page needed.
--
-- 000000 IS A PLACEHOLDER (the user: "add a mock one but will change on text"). Change the number on Message templates
-- before go-live. Only a text still as seeded is changed here, so one already edited on that screen is left alone.

UPDATE communication.notification_template
SET body_template = 'Your cover offer {{policyNumber}} is ready. Pay {{premium}} by {{expiryDate}} to M-Pesa Lipa Namba 000000, reference {{policyNumber}}. You are not covered yet.'
WHERE template_key = 'OFFER_MADE' AND channel = 'SMS' AND language = 'en'
  AND body_template = 'Your cover offer {{policyNumber}} is ready. Pay {{premium}} by {{expiryDate}} to start it. You are not covered yet.';

UPDATE communication.notification_template
SET body_template = 'Ofa ya bima {{policyNumber}} iko tayari. Lipa {{premium}} kabla ya {{expiryDate}} kwa M-Pesa Lipa Namba 000000, kumbukumbu {{policyNumber}}. Hujaanza kulindwa bado.'
WHERE template_key = 'OFFER_MADE' AND channel = 'SMS' AND language = 'sw'
  AND body_template = 'Ofa ya bima {{policyNumber}} iko tayari. Lipa {{premium}} kabla ya {{expiryDate}} ili ulinzi uanze. Hujaanza kulindwa bado.';

UPDATE communication.notification_template
SET body_template = 'Reminder: your cover offer {{policyNumber}} closes on {{expiryDate}}. Pay to M-Pesa Lipa Namba 000000, reference {{policyNumber}}, to start your cover.'
WHERE template_key = 'OFFER_CLOSING' AND channel = 'SMS' AND language = 'en'
  AND body_template = 'Reminder: your cover offer {{policyNumber}} closes on {{expiryDate}}. Pay before then to start your cover.';

UPDATE communication.notification_template
SET body_template = 'Kumbusho: ofa ya bima {{policyNumber}} inafungwa {{expiryDate}}. Lipa kwa M-Pesa Lipa Namba 000000, kumbukumbu {{policyNumber}}, ili ulinzi uanze.'
WHERE template_key = 'OFFER_CLOSING' AND channel = 'SMS' AND language = 'sw'
  AND body_template = 'Kumbusho: ofa ya bima {{policyNumber}} inafungwa {{expiryDate}}. Lipa kabla ya tarehe hiyo ili ulinzi uanze.';
