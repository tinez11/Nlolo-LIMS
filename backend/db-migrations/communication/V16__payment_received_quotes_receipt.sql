-- db-migrations/communication/V16__payment_received_quotes_receipt.sql
-- The payment-received message quotes its receipt number (receipts step 1, 2026-11-05). It is sent once per amount
-- received (payment.ReceiptIssued), no longer when an invoice reaches PAID, and the number is what the customer quotes
-- in any question about the money -- the same number on the receipt PDF and in the console.
-- Only the platform's default rows (the zero tenant); a tenant's own wording is theirs to change.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

UPDATE communication.notification_template SET body_template =
    'Tumepokea malipo ya {{amount}} kwa bima {{policyNumber}}. Risiti {{receiptNumber}}. Asante.'
 WHERE tenant_id = '00000000-0000-0000-0000-000000000000' AND template_key = 'PAYMENT_RECEIVED'
   AND channel = 'SMS' AND language = 'sw';
UPDATE communication.notification_template SET body_template =
    'We have received your payment of {{amount}} for policy {{policyNumber}}. Receipt {{receiptNumber}}. Thank you.'
 WHERE tenant_id = '00000000-0000-0000-0000-000000000000' AND template_key = 'PAYMENT_RECEIVED'
   AND channel = 'SMS' AND language = 'en';
UPDATE communication.notification_template SET body_template =
    'Tumepokea malipo ya {{amount}} kwa bima {{policyNumber}}. Risiti {{receiptNumber}}. Asante. Hifadhi ujumbe huu kama uthibitisho wa malipo yako.'
 WHERE tenant_id = '00000000-0000-0000-0000-000000000000' AND template_key = 'PAYMENT_RECEIVED'
   AND channel = 'EMAIL' AND language = 'sw';
UPDATE communication.notification_template SET body_template =
    'We have received your payment of {{amount}} for policy {{policyNumber}}. Receipt {{receiptNumber}}. Thank you. Please keep this message as confirmation of your payment.'
 WHERE tenant_id = '00000000-0000-0000-0000-000000000000' AND template_key = 'PAYMENT_RECEIVED'
   AND channel = 'EMAIL' AND language = 'en';
