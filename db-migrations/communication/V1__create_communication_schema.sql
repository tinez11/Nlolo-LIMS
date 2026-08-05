-- Module: communication (Customer Communications) -- Deliverable 3 §10
-- Owns: notification_template, notification_dispatch, processed_event (idempotency dedup)

CREATE SCHEMA IF NOT EXISTS communication;

CREATE TABLE communication.notification_template (
    template_key          VARCHAR(50) PRIMARY KEY,
    tenant_id              UUID NOT NULL,
    channel                  VARCHAR(15) NOT NULL CHECK (channel IN ('SMS','USSD','EMAIL','PUSH')),
    language                  VARCHAR(5) NOT NULL CHECK (language IN ('sw','en')),   -- Swahili customer-facing, English back-office
    body_template               TEXT NOT NULL,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_template_tenant ON communication.notification_template (tenant_id);

CREATE TABLE communication.notification_dispatch (
    dispatch_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL,
    party_id                  UUID NOT NULL,   -- opaque ref into party
    template_key                VARCHAR(50) NOT NULL REFERENCES communication.notification_template(template_key),
    channel                      VARCHAR(15) NOT NULL,
    status                        VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    dispatched_at                   TIMESTAMPTZ,
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_dispatch_party ON communication.notification_dispatch (party_id);
CREATE INDEX idx_notification_dispatch_tenant ON communication.notification_dispatch (tenant_id);

-- Deliverable 5 §4: communication is the module singled out as needing explicit
-- processed-event dedup (rather than relying on natural idempotency), since a resent
-- SMS is a real user-visible duplicate, not a harmless no-op.
CREATE TABLE communication.processed_event (
    event_id               UUID PRIMARY KEY,
    processed_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
