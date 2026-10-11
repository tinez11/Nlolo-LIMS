-- db-migrations/reporting/V1__create_reporting_schema.sql
-- The reporting module's read model (docs/superpowers/specs/2026-10-11-reporting-management-design.md
-- section 5). Every row is projected from a domain event, or re-derived by replaying the audit journal,
-- so nothing here is the record of anything: deleting a tenant's rows and rebuilding is always safe.
-- No foreign key into another module's tables -- a policy number or party id is carried as a value.
CREATE SCHEMA IF NOT EXISTS reporting;
GRANT USAGE ON SCHEMA reporting TO app_role;

-- One row per event already applied. Inserted in the same transaction as the event's rows, so a
-- redelivered or replayed event is a no-op.
CREATE TABLE reporting.applied_event (
    tenant_id  UUID NOT NULL,
    event_id   UUID NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, event_id)
);

-- One row per policy. No status column on purpose: status is always derived from
-- report_policy_status_change, so the order events arrive in cannot leave a stale status behind.
-- product_id 00000000-0000-0000-0000-000000000000 marks a policy seen only through a later event.
CREATE TABLE reporting.report_policy (
    tenant_id          UUID NOT NULL,
    policy_number      VARCHAR(30) NOT NULL,
    holder_party_id    UUID,
    product_id         UUID NOT NULL,
    product_version_id UUID,
    product_category   VARCHAR(40),
    portfolio_code     VARCHAR(10),
    group_scheme       BOOLEAN NOT NULL DEFAULT FALSE,
    agent_id           UUID,
    sales_channel      VARCHAR(40),
    branch_code        VARCHAR(40),
    sum_assured        NUMERIC(19,2),
    premium            NUMERIC(19,2),
    premium_frequency  VARCHAR(20),
    currency           CHAR(3) NOT NULL DEFAULT 'TZS',
    issue_date         DATE,
    activated_on       DATE,
    not_taken_up_on    DATE,
    PRIMARY KEY (tenant_id, policy_number)
);
CREATE INDEX ix_report_policy_activated ON reporting.report_policy (tenant_id, activated_on);
CREATE INDEX ix_report_policy_holder ON reporting.report_policy (tenant_id, holder_party_id);

CREATE TABLE reporting.report_policy_status_change (
    tenant_id     UUID NOT NULL,
    event_id      UUID NOT NULL,
    policy_number VARCHAR(30) NOT NULL,
    to_status     VARCHAR(25) NOT NULL CHECK (to_status IN ('ACTIVE','SUSPENDED','PAID_UP','LAPSED','MATURED',
                      'EXPIRED','CLAIM_TERMINATED','SURRENDERED','CANCELLED_FREE_LOOK','ENDED')),
    cause         VARCHAR(60) NOT NULL,
    effective_on  DATE NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX ix_status_change_policy ON reporting.report_policy_status_change (tenant_id, policy_number, effective_on);
CREATE INDEX ix_status_change_effective ON reporting.report_policy_status_change (tenant_id, effective_on);

CREATE TABLE reporting.report_collection (
    tenant_id     UUID NOT NULL,
    event_id      UUID NOT NULL,
    invoice_id    UUID,
    policy_number VARCHAR(30) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL,
    currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    collected_on  DATE NOT NULL,
    channel       VARCHAR(20) NOT NULL CHECK (channel IN ('MOBILE_MONEY','FIELD_CASH')),
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX ix_collection_date ON reporting.report_collection (tenant_id, collected_on);

CREATE TABLE reporting.report_client (
    tenant_id     UUID NOT NULL,
    party_id      UUID NOT NULL,
    party_type    VARCHAR(15) NOT NULL CHECK (party_type IN ('INDIVIDUAL','CORPORATE','GROUP')),
    kyc_status    VARCHAR(15),
    display_name  VARCHAR(300),
    registered_on DATE,
    date_of_birth DATE,
    sex           VARCHAR(10),
    PRIMARY KEY (tenant_id, party_id)
);

-- One row per member or covered-life change. GroupMemberAdded carries no member id, so members are
-- not tracked one by one; every such event carries livesCovered, the lives on the policy AFTER it.
CREATE TABLE reporting.report_lives_change (
    tenant_id            UUID NOT NULL,
    event_id             UUID NOT NULL,
    policy_number        VARCHAR(30) NOT NULL,
    kind                 VARCHAR(10) NOT NULL CHECK (kind IN ('ACTIVATED','JOINED','EXITED')),
    lives_covered        INTEGER NOT NULL CHECK (lives_covered >= 0),
    scheme_total_covered NUMERIC(19,2),
    effective_on         DATE NOT NULL,
    occurred_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX ix_lives_change_policy ON reporting.report_lives_change (tenant_id, policy_number, effective_on);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['applied_event','report_policy','report_policy_status_change',
                             'report_collection','report_client','report_lives_change'] LOOP
        EXECUTE format('ALTER TABLE reporting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON reporting.%I USING (tenant_id = '
            'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
    END LOOP;
END $$;

-- DELETE is granted because a rebuild clears the tenant's rows before replaying the journal.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reporting TO app_role;
