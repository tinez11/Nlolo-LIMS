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
-- GRANT USAGE ON SCHEMA is load-bearing here: without it the table-level grant below
-- cannot be exercised at all (schema USAGE gates every access to objects inside it,
-- independent of table-level GRANTs) -- migrations run as the postgres superuser
-- (scripts/migrate.sh), which becomes owner of this schema, so app_role otherwise
-- has zero access to it.
GRANT USAGE ON SCHEMA audit TO app_role;
REVOKE UPDATE, DELETE ON audit.audit_log FROM app_role;
GRANT INSERT, SELECT ON audit.audit_log TO app_role;

-- Row-Level Security -- audit stores every event payload from every module, for every
-- tenant, so it is not a declared exception to the tenant-isolation Global Constraint
-- the way refdata is; the only tenant filter previously in place was the application-level
-- WHERE clause in AuditApiImpl.getTrail, which is not defense-in-depth on its own.
ALTER TABLE audit.audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_log_tenant_isolation ON audit.audit_log
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

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

-- app_role privileges -- as of M1, DomainEventAuditListener.recordFailure() only ever
-- inserts a new row (JpaRepository.save() on a @GeneratedValue id, i.e. entityManager
-- .persist(), not merge); there is no reader or retry/resolve writer against this table
-- yet anywhere in the codebase (the "staff monitoring view" and retry mechanism mentioned
-- above are not implemented in M1). Grant is scoped to that actual usage rather than the
-- full column set (retry_count/resolved_at) the eventual monitoring/retry feature will
-- need -- widen this grant (add SELECT, and UPDATE for the retry/resolve path) in the
-- same migration that introduces that feature.
GRANT INSERT ON audit.failed_event TO app_role;

-- Row-Level Security -- same rationale as audit_log above: this table holds every
-- tenant's dead-lettered event payloads and has its own tenant_id column, so it gets
-- the same policy shape as every other tenant-scoped table on the platform.
ALTER TABLE audit.failed_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY failed_event_tenant_isolation ON audit.failed_event
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
