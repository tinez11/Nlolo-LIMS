-- db-migrations/communication/V15__dispatch_body_and_inbox.sql
-- The customer portal's messages (2026-10-08, the customer portal design step 7).
--
-- A dispatch recorded that a message was owed and whether it went, but not what it SAID -- the template it came from is
-- editable, so its body today is not the text the customer received. The portal's inbox shows the customer what we told
-- them, so the rendered text is now kept on the row that sent it. Messages sent before this have none; the inbox gives
-- those a title only.
--
-- event_id groups the SMS and the email one event produced, so the inbox shows the message once. read_at is when the
-- customer opened it in the portal -- nothing to do with delivery, which SENT already caps at "accepted by the transport".

ALTER TABLE communication.notification_dispatch ADD COLUMN IF NOT EXISTS body TEXT;
ALTER TABLE communication.notification_dispatch ADD COLUMN IF NOT EXISTS event_id UUID;
ALTER TABLE communication.notification_dispatch ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_notification_dispatch_party_created
    ON communication.notification_dispatch (tenant_id, party_id, created_at DESC);
