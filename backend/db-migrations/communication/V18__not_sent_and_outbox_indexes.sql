-- db-migrations/communication/V18__not_sent_and_outbox_indexes.sql
-- 2026-11-08, the user: at 10,000 customers the outbox holds a million rows a year.
--
-- NOT_SENT: a message deliberately not sent -- its channel switched off, or its recipient outside a testing
-- allowlist. A setting, not a fault: FAILED keeps meaning something worth acting on (on dev, 224 of 435 rows were
-- "SMS sending is switched off", each read as a failure). Earlier rows saying so become NOT_SENT.
--
-- Indexes for the register's own reads -- the newest of a tenant, by status, by message, by channel -- so the latest
-- page stays instant at any size instead of sorting the whole outbox to find it.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE communication.notification_dispatch DROP CONSTRAINT IF EXISTS notification_dispatch_status_check;
ALTER TABLE communication.notification_dispatch
    ADD CONSTRAINT notification_dispatch_status_check
    CHECK (status IN ('PENDING', 'CLAIMED', 'SENT', 'FAILED', 'NOT_SENT'));

UPDATE communication.notification_dispatch
   SET status = 'NOT_SENT'
 WHERE status = 'FAILED'
   AND (failure_reason LIKE 'SMS sending is switched off%'
        OR failure_reason LIKE 'Email is switched off%'
        OR failure_reason LIKE 'Recipient % is not on the SMS testing allowlist');

-- The same words the platform now writes, rather than the setting's name.
UPDATE communication.notification_dispatch SET failure_reason = 'SMS sending is switched off'
 WHERE failure_reason = 'SMS sending is switched off (communication.sms-gateway.live=false)';
UPDATE communication.notification_dispatch SET failure_reason = 'Email is not in use'
 WHERE failure_reason = 'Email is switched off (communication.email.enabled=false)';

CREATE INDEX IF NOT EXISTS idx_notification_dispatch_tenant_created
    ON communication.notification_dispatch (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notification_dispatch_tenant_status_created
    ON communication.notification_dispatch (tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notification_dispatch_tenant_key_created
    ON communication.notification_dispatch (tenant_id, template_key, created_at DESC);
-- The single-column tenant index is a prefix of the first above.
DROP INDEX IF EXISTS communication.idx_notification_dispatch_tenant;
