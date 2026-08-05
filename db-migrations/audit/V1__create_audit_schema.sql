-- Module: audit (Audit & Compliance Trail) -- Deliverable 3 Rev 2 §9 (module #18, Deliverable 2 Rev 2 D1)
-- Owns: audit_log (partitioned, append-only, WORM)

CREATE SCHEMA IF NOT EXISTS audit;

-- Single generic listener persists every DomainEventEnvelope<?> published platform-wide
-- (Deliverable 3 Rev 2, §9) -- payload stored as JSONB rather than requiring a typed
-- column per event, so this table needs no migration when a 58th event type is added.
CREATE TABLE audit.audit_log (
    audit_log_id        UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    event_id             UUID NOT NULL,
    event_type           VARCHAR(100) NOT NULL,          -- e.g. 'policy.PolicyIssued'
    schema_version       INTEGER NOT NULL,
    sequence_number       BIGINT,                          -- per-aggregate, nullable for non-aggregate events
    occurred_at           TIMESTAMPTZ NOT NULL,
    recorded_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload               JSONB NOT NULL,
    PRIMARY KEY (audit_log_id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE audit.audit_log_2026_08 PARTITION OF audit.audit_log
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE audit.audit_log_2026_09 PARTITION OF audit.audit_log
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
-- Monthly partitions, same operational convention as policyloan.loan_transaction --
-- this is the single highest-volume table in the entire platform (every event, from
-- every module, lands here). Retention policy (how long partitions are kept before
-- archival/drop) is a compliance decision, not an architecture one -- flagged as an
-- open item in the covering doc rather than guessed at here.

CREATE INDEX idx_audit_log_tenant ON audit.audit_log (tenant_id);
CREATE INDEX idx_audit_log_event_type ON audit.audit_log (event_type, occurred_at);
CREATE INDEX idx_audit_log_event_id ON audit.audit_log (event_id);   -- supports idempotent-insert dedup check on redelivery

-- WORM enforcement (Deliverable 3 Rev 2, A2 -- explicit REVOKE, as requested).
-- app_role is the application's runtime DB role; only INSERT/SELECT are granted.
REVOKE UPDATE, DELETE ON audit.audit_log FROM app_role;
GRANT INSERT, SELECT ON audit.audit_log TO app_role;

-- Dead-letter table for events that exhausted retry (Deliverable 5, §4) -- surfaced to
-- a staff monitoring view; not partitioned (expected to be low-volume relative to
-- audit_log itself, since it only holds genuine failures).
CREATE TABLE audit.failed_event (
    failed_event_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    event_id              UUID NOT NULL,
    event_type            VARCHAR(100) NOT NULL,
    consumer_module        VARCHAR(50) NOT NULL,
    failure_reason         TEXT,
    retry_count             INTEGER NOT NULL DEFAULT 0,
    first_failed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_retried_at          TIMESTAMPTZ,
    resolved_at              TIMESTAMPTZ,
    payload                  JSONB NOT NULL
);
CREATE INDEX idx_failed_event_unresolved ON audit.failed_event (tenant_id) WHERE resolved_at IS NULL;
