-- db-migrations/audit/V5__activity.sql
-- 2026-11-14, activity log: who did what, when, to what, and whether it worked. One row per write request a
-- signed-in person (staff, agent or customer) made, written by the audit module's ActivityRecordingFilter. Events
-- raised by that request carry its id (audit_log.action_id), so an admin sees what followed from it.
--
-- Kept forever and never changed: app_role may insert and read only, as for audit_log. Retention stays the open
-- compliance decision recorded in audit/V1; partman makes partitions ahead and drops none.

CREATE TABLE audit.activity (
    activity_id     UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    actor_subject   VARCHAR(100),               -- the token subject (for staff, iam.staff_user.user_id)
    actor_name      VARCHAR(200),               -- the display name at the time (TokenNames.displayName)
    actor_kind      VARCHAR(20) NOT NULL,       -- STAFF | AGENT | CUSTOMER
    http_method     VARCHAR(10) NOT NULL,
    route           VARCHAR(300) NOT NULL,      -- the matched pattern, e.g. /claims/{claimId}/approve
    label           VARCHAR(200) NOT NULL,      -- audit/activity-labels.tsv, in words
    kind            VARCHAR(30) NOT NULL,       -- claims | money | policies | people | products | settings | other
    record_type     VARCHAR(40),
    record_id       VARCHAR(100),
    record_label    VARCHAR(200),
    amount          NUMERIC(19,2),
    currency        VARCHAR(3),
    body            JSONB,                      -- as sent, secrets removed; personal numbers masked on read
    outcome         VARCHAR(10) NOT NULL,       -- DONE | REFUSED | FAILED
    status_code     INTEGER NOT NULL,
    reason          TEXT,                       -- the words the person saw, on REFUSED
    PRIMARY KEY (activity_id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Hand-written months to 2027-03, guarded as audit/V3 is: a partman-managed database already has them, and a plain
-- CREATE would abort the migration there.
DO $$
DECLARE
    month_start DATE := DATE '2026-11-01';
BEGIN
    WHILE month_start < DATE '2027-04-01' LOOP
        BEGIN
            EXECUTE format(
                'CREATE TABLE audit.%I PARTITION OF audit.activity FOR VALUES FROM (%L) TO (%L)',
                'activity_' || to_char(month_start, 'YYYY_MM'),
                month_start,
                (month_start + INTERVAL '1 month')::date);
        EXCEPTION
            WHEN invalid_object_definition OR duplicate_table THEN
                RAISE NOTICE 'activity already covers %, leaving it alone', month_start;
        END;
        month_start := (month_start + INTERVAL '1 month')::date;
    END LOOP;
END $$;

CREATE INDEX idx_activity_tenant_time ON audit.activity (tenant_id, occurred_at DESC);
CREATE INDEX idx_activity_actor ON audit.activity (tenant_id, actor_subject, occurred_at DESC);
CREATE INDEX idx_activity_record ON audit.activity (tenant_id, record_type, record_id, occurred_at DESC);

REVOKE UPDATE, DELETE ON audit.activity FROM app_role;
GRANT INSERT, SELECT ON audit.activity TO app_role;

ALTER TABLE audit.activity ENABLE ROW LEVEL SECURITY;
CREATE POLICY activity_tenant_isolation ON audit.activity
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- The action that raised an event, when a person's request did. Null for scheduled runs and for every event recorded
-- before this column existed.
ALTER TABLE audit.audit_log ADD COLUMN IF NOT EXISTS action_id UUID;
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit.audit_log (action_id);
