-- db-migrations/bonus/V1__create_bonus_schema.sql
-- Product step 4: with-profits bonuses. This module owns declarations, every attached bonus and
-- every movement in that balance, and its own record of each participating policy's status --
-- policy keeps none, and eligibility (Q3) is judged on the status at a PAST valuation date.
-- No foreign key into another module's tables: a policy number is carried as a value.
CREATE SCHEMA IF NOT EXISTS bonus;
GRANT USAGE ON SCHEMA bonus TO app_role;

CREATE TABLE bonus.declaration (
    declaration_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID NOT NULL,
    product_id                UUID NOT NULL,
    valuation_date            DATE NOT NULL,
    reversionary_rate_percent NUMERIC(7,4) NOT NULL CHECK (reversionary_rate_percent BETWEEN 0 AND 100),
    -- A % OF ATTACHED BONUSES, so above 100 is a real declaration (a terminal bonus of 150%).
    terminal_rate_percent     NUMERIC(7,4) NOT NULL CHECK (terminal_rate_percent BETWEEN 0 AND 1000),
    status                    VARCHAR(10) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','APPROVED','WITHDRAWN')),
    proposed_by               VARCHAR(100) NOT NULL,
    proposed_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by               VARCHAR(100),
    approved_at               TIMESTAMPTZ,
    -- Set by the drain when every participating policy on the product has an outcome.
    completed_at              TIMESTAMPTZ,
    version                   BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT declaration_two_person CHECK (approved_by IS NULL OR approved_by <> proposed_by),
    CONSTRAINT declaration_approved_shape CHECK ((status = 'APPROVED') = (approved_by IS NOT NULL))
);
CREATE UNIQUE INDEX ux_declaration_valuation ON bonus.declaration (product_id, valuation_date) WHERE status = 'APPROVED';

-- One per PARTICIPATING policy, written on PolicyIssued. last_seq and attached_total are the
-- ledger's RUNNING HEAD, accumulation.account's shape: a copy kept in the same transaction as each
-- entry, which the follows trigger below stops from drifting.
CREATE TABLE bonus.participant (
    policy_number      VARCHAR(20) PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    product_id         UUID NOT NULL,
    product_version_id UUID NOT NULL,
    currency           CHAR(3) NOT NULL DEFAULT 'TZS',
    issued_on          DATE NOT NULL,
    last_seq           INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    attached_total     NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (attached_total >= 0),
    version            BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_participant_product ON bonus.participant (product_id);

-- The status record. sum_assured is set only where the event states it (issue, paid-up); the sum
-- assured on a date is the latest non-null one at or before it. event_id makes a redelivery a no-op.
CREATE TABLE bonus.status_event (
    status_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    event_id        UUID NOT NULL,
    policy_number   VARCHAR(20) NOT NULL,
    status          VARCHAR(25) NOT NULL,
    sum_assured     NUMERIC(19,2) CHECK (sum_assured IS NULL OR sum_assured >= 0),
    effective_at    TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_status_event_event ON bonus.status_event (tenant_id, event_id);
CREATE INDEX ix_status_event_policy ON bonus.status_event (policy_number, effective_at);

-- One per (declaration, policy): attached, not eligible (with why), or nothing due. The unique
-- index is what lets the drain resume after a crash without attaching twice, and the reason is
-- what the Bonuses tab shows a person who asks why a policy got nothing.
CREATE TABLE bonus.declaration_outcome (
    outcome_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    declaration_id UUID NOT NULL REFERENCES bonus.declaration(declaration_id),
    policy_number  VARCHAR(20) NOT NULL,
    outcome        VARCHAR(15) NOT NULL CHECK (outcome IN ('ATTACHED','NOT_ELIGIBLE','NOTHING_DUE')),
    reason         VARCHAR(300),
    decided_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT outcome_reason_shape CHECK ((outcome = 'NOT_ELIGIBLE') = (reason IS NOT NULL))
);
CREATE UNIQUE INDEX ux_declaration_outcome ON bonus.declaration_outcome (declaration_id, policy_number);

CREATE TABLE bonus.attachment_entry (
    entry_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    policy_number     VARCHAR(20) NOT NULL,
    seq               INTEGER NOT NULL CHECK (seq >= 1),
    entry_type        VARCHAR(15) NOT NULL CHECK (entry_type IN ('REVERSIONARY','REVERSAL')),
    amount            NUMERIC(19,2) NOT NULL,
    total_after       NUMERIC(19,2) NOT NULL CHECK (total_after >= 0),
    -- The valuation date of the declaration it came from; a reversal's own date.
    effective_date    DATE NOT NULL,
    declaration_id    UUID REFERENCES bonus.declaration(declaration_id),
    basis_amount      NUMERIC(19,2),
    rate_percent      NUMERIC(7,4),
    source_type       VARCHAR(20) NOT NULL,
    source_ref        VARCHAR(100) NOT NULL,
    reverses_entry_id UUID REFERENCES bonus.attachment_entry(entry_id),
    reason            VARCHAR(300),
    created_by        VARCHAR(100) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT attachment_reversal_shape CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL)),
    CONSTRAINT attachment_reversionary_shape CHECK (entry_type <> 'REVERSIONARY'
        OR (declaration_id IS NOT NULL AND basis_amount IS NOT NULL AND rate_percent IS NOT NULL AND amount > 0))
);
-- ux_attachment_source IS the once-only guarantee (the user's third rule).
CREATE UNIQUE INDEX ux_attachment_source ON bonus.attachment_entry (tenant_id, source_type, source_ref);
CREATE UNIQUE INDEX ux_attachment_seq ON bonus.attachment_entry (policy_number, seq);
CREATE UNIQUE INDEX ux_attachment_reversed_once ON bonus.attachment_entry (reverses_entry_id) WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX ix_attachment_policy_date ON bonus.attachment_entry (policy_number, effective_date);

CREATE TABLE bonus.settlement (
    settlement_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    policy_number         VARCHAR(20) NOT NULL,
    exit_type             VARCHAR(10) NOT NULL CHECK (exit_type IN ('MATURITY','DEATH')),
    exit_ref              VARCHAR(100) NOT NULL,
    exit_date             DATE NOT NULL,
    attached_amount       NUMERIC(19,2) NOT NULL CHECK (attached_amount >= 0),
    interim_amount        NUMERIC(19,2) NOT NULL CHECK (interim_amount >= 0),
    terminal_amount       NUMERIC(19,2) NOT NULL CHECK (terminal_amount >= 0),
    interim_rate_percent  NUMERIC(7,4),
    terminal_rate_percent NUMERIC(7,4),
    declaration_id        UUID REFERENCES bonus.declaration(declaration_id),
    recorded_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_settlement_exit ON bonus.settlement (tenant_id, exit_type, exit_ref);

-- One row per Idempotency-Key a create request carried (proposing a declaration), accumulation V2's
-- shape and reason: step 3's live check found a retried request created twice because no endpoint
-- read the key the console sends. The create and its key commit in one transaction.
CREATE TABLE bonus.request_key (
    tenant_id       UUID NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    operation       VARCHAR(30) NOT NULL,
    target          VARCHAR(100) NOT NULL,
    resource_id     UUID NOT NULL,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key)
);

-- IMMUTABILITY, twice, accumulation's arrangement: grants bind app_role, this trigger binds the
-- owner every migration and test connects as.
CREATE OR REPLACE FUNCTION bonus.refuse_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'bonus.% is append-only: correct it with a reversing entry, never an %', TG_TABLE_NAME, TG_OP;
END $$;
CREATE TRIGGER attachment_entry_append_only BEFORE UPDATE OR DELETE ON bonus.attachment_entry
    FOR EACH ROW EXECUTE FUNCTION bonus.refuse_mutation();
CREATE TRIGGER declaration_outcome_append_only BEFORE UPDATE OR DELETE ON bonus.declaration_outcome
    FOR EACH ROW EXECUTE FUNCTION bonus.refuse_mutation();
CREATE TRIGGER status_event_append_only BEFORE UPDATE OR DELETE ON bonus.status_event
    FOR EACH ROW EXECUTE FUNCTION bonus.refuse_mutation();
CREATE TRIGGER settlement_append_only BEFORE UPDATE OR DELETE ON bonus.settlement
    FOR EACH ROW EXECUTE FUNCTION bonus.refuse_mutation();

CREATE OR REPLACE FUNCTION bonus.check_entry_follows() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous NUMERIC(19,2);
BEGIN
    IF NEW.seq = 1 THEN
        previous := 0;
    ELSE
        SELECT total_after INTO previous FROM bonus.attachment_entry
         WHERE policy_number = NEW.policy_number AND seq = NEW.seq - 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'bonus entry % for policy % has no entry % before it', NEW.seq, NEW.policy_number, NEW.seq - 1;
        END IF;
    END IF;
    IF NEW.total_after <> previous + NEW.amount THEN
        RAISE EXCEPTION 'bonus entry % for policy %: total_after % is not % + %',
            NEW.seq, NEW.policy_number, NEW.total_after, previous, NEW.amount;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER attachment_entry_follows BEFORE INSERT ON bonus.attachment_entry
    FOR EACH ROW EXECUTE FUNCTION bonus.check_entry_follows();

-- The drain's cross-tenant selector: ids only.
CREATE OR REPLACE FUNCTION bonus.declarations_due()
RETURNS TABLE (declaration_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT d.declaration_id, d.tenant_id FROM bonus.declaration d
     WHERE d.status = 'APPROVED' AND d.completed_at IS NULL AND d.valuation_date <= current_date
     ORDER BY d.valuation_date
     LIMIT 50;
$$;
REVOKE EXECUTE ON FUNCTION bonus.declarations_due() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bonus.declarations_due() TO app_role;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['declaration','participant','status_event','declaration_outcome',
                             'attachment_entry','settlement','request_key'] LOOP
        EXECUTE format('ALTER TABLE bonus.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON bonus.%I USING (tenant_id = '
            'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON bonus.declaration, bonus.participant TO app_role;
-- The record itself: read and append, nothing else.
GRANT SELECT, INSERT ON bonus.status_event, bonus.declaration_outcome, bonus.attachment_entry, bonus.settlement,
    bonus.request_key TO app_role;
