-- db-migrations/communication/V5__dispatch_claimed_status.sql
-- Taken by a sender, not yet resolved.
--
-- The reminder queue needs a fourth state. PENDING rows are produced by a cross-tenant pg_cron
-- sweep and drained by OfferReminderDispatcher, and a read-then-send in Java would let two
-- application instances both pass the "is it PENDING" check and both send -- a duplicate SMS,
-- which is the one failure this module exists to avoid. The claim is therefore a conditional
-- UPDATE, and it needs somewhere to move the row to.
--
-- CLAIMED is non-terminal on purpose, and the failure mode is chosen rather than accepted: a
-- process that dies mid-send leaves the row CLAIMED rather than PENDING, so the customer misses
-- one reminder instead of receiving a fresh one on every subsequent pass for the rest of the
-- offer's life. An operator can see the row and its age in the outbox either way.
--
-- Event-driven notifications never enter this state. They are sent inline by NotificationApi,
-- which writes SENT or FAILED directly and dedups on the event id.
ALTER TABLE communication.notification_dispatch DROP CONSTRAINT notification_dispatch_status_check;
ALTER TABLE communication.notification_dispatch ADD CONSTRAINT notification_dispatch_status_check
    CHECK (status IN ('PENDING','CLAIMED','SENT','FAILED'));

-- The drain's own query. Tiny and highly selective: PENDING rows exist for minutes a day.
CREATE INDEX idx_notification_dispatch_pending
    ON communication.notification_dispatch (status, created_at) WHERE status = 'PENDING';

COMMENT ON COLUMN communication.notification_dispatch.status IS
    'PENDING (queued by the reminder sweep), CLAIMED (a sender has taken it), SENT (accepted by the transport -- never "delivered"), FAILED (with a reason).';
