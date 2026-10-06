-- db-migrations/reinsurance/V7__statement.sql
-- IFRS 17 I3d (guide R-01, R-03, R-04): the quarterly statement settling a treaty. The platform's figures (the
-- quarter's bordereaux and recoveries) plus what finance enters from the reinsurer's statement (funds withheld,
-- profit commission). Approved by a FINANCE_APPROVER who is not the preparer; posted as one SYSTEM journal
-- (posting rules v4, R-STMT).

CREATE TABLE reinsurance.statement (
    statement_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    treaty_id         UUID NOT NULL REFERENCES reinsurance.reinsurance_treaty (treaty_id),
    quarter           VARCHAR(7) NOT NULL CHECK (quarter ~ '^\d{4}-Q[1-4]$'),
    currency          CHAR(3) NOT NULL,
    status            VARCHAR(10) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
    premium           NUMERIC(19,2) NOT NULL CHECK (premium >= 0),
    commission        NUMERIC(19,2) NOT NULL CHECK (commission >= 0),
    recoveries        NUMERIC(19,2) NOT NULL CHECK (recoveries >= 0),
    funds_withheld    NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (funds_withheld >= 0),
    profit_commission NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (profit_commission >= 0),
    reason            VARCHAR(500),
    document_refs     TEXT[] NOT NULL DEFAULT '{}',
    preparer          VARCHAR(100) NOT NULL,
    prepared_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at      TIMESTAMPTZ,
    decided_by        VARCHAR(100),
    decided_at        TIMESTAMPTZ,
    decision_reason   VARCHAR(500),
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT statement_withheld_within_premium CHECK (funds_withheld <= premium),
    CONSTRAINT statement_decider_not_preparer CHECK (decided_by IS NULL OR decided_by <> preparer),
    CONSTRAINT statement_rejection_has_reason CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL)
);
-- One live (not rejected) statement per treaty and quarter.
CREATE UNIQUE INDEX ux_statement_live ON reinsurance.statement (tenant_id, treaty_id, quarter)
    WHERE status <> 'REJECTED';
CREATE INDEX idx_statement_status ON reinsurance.statement (tenant_id, status, prepared_at);

-- What a statement settles. `live` follows the statement (false once it is rejected), so each bordereau and each
-- recovery is on at most one live statement.
CREATE TABLE reinsurance.statement_item (
    statement_id  UUID NOT NULL REFERENCES reinsurance.statement (statement_id),
    tenant_id     UUID NOT NULL,
    item_type     VARCHAR(10) NOT NULL CHECK (item_type IN ('BORDEREAU','RECOVERY')),
    item_id       UUID NOT NULL,
    live          BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (statement_id, item_type, item_id)
);
CREATE UNIQUE INDEX ux_statement_item_once ON reinsurance.statement_item (tenant_id, item_type, item_id) WHERE live;

-- Recoveries recorded before I3c never posted 1420 (I3c answer Q4): a statement must not clear what was never there.
-- A recovery is legacy when no reinsurance.RecoveryCalculated journal names it.
ALTER TABLE reinsurance.claim_recovery ADD COLUMN legacy BOOLEAN NOT NULL DEFAULT false;
DO $$
BEGIN
    IF to_regclass('finaccounting.journal_entry') IS NULL THEN
        RETURN;   -- a module test without the ledger: nothing was ever posted, so nothing to mark
    END IF;
    UPDATE reinsurance.claim_recovery r SET legacy = true
     WHERE NOT EXISTS (SELECT 1 FROM finaccounting.journal_entry j
                        WHERE j.tenant_id = r.tenant_id AND j.source_event = 'reinsurance.RecoveryCalculated'
                          AND j.source_ref = r.recovery_id::text);
END;
$$;

ALTER TABLE reinsurance.statement ENABLE ROW LEVEL SECURITY;
CREATE POLICY statement_tenant_isolation ON reinsurance.statement
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE reinsurance.statement_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY statement_item_tenant_isolation ON reinsurance.statement_item
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON reinsurance.statement, reinsurance.statement_item TO app_role;
