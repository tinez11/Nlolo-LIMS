-- A lender's monthly schedule, as submitted and as judged.
--
-- Nothing here enrols anybody. A submission is a PROPOSAL: every row is parsed, judged
-- and recorded with its outcome, and a SECOND staff user turns it into cover. Spec §2.10
-- -- a counterparty with a direct financial interest in maximising cover must not create
-- the insurer's liability unattended, and because cover backdates to disbursement the
-- wait costs throughput rather than risk.

-- Repayment cadence moves from the file to the scheme. A lender's product repays on one
-- cadence, so asking for it on every one of 400 rows is 400 chances for a file to
-- disagree with itself. It sits beside interest_method and follows the same rule: no
-- setter on the entity, because every member already on the roll had their schedule
-- counted against the old answer.
ALTER TABLE policy.group_scheme
    ADD COLUMN repayment_frequency VARCHAR(20)
        CHECK (repayment_frequency IS NULL
               OR repayment_frequency IN ('MONTHLY','QUARTERLY'));

-- Backfilled rather than defaulted: every credit-life scheme that exists today is
-- monthly, and a column added to a populated table cannot be NOT NULL without one.
UPDATE policy.group_scheme SET repayment_frequency = 'MONTHLY'
 WHERE benefit_basis = 'AMORTISING_LOAN' AND repayment_frequency IS NULL;

CREATE TABLE policy.enrolment_submission (
    submission_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    policy_number    VARCHAR(20) NOT NULL REFERENCES policy.group_scheme(policy_number),

    -- The lender's own file, byte for byte. Kept for the reason a claim keeps its
    -- evidence: when a lender disputes whether a borrower was declared, the answer is
    -- what they sent, not our reading of it.
    --
    -- VARCHAR(255) to match document.document_record.document_ref exactly, checked
    -- against the live column rather than estimated.
    document_ref     VARCHAR(255) NOT NULL,
    file_name        VARCHAR(255),

    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','WITHDRAWN')),

    row_count        INTEGER NOT NULL CHECK (row_count >= 0),
    enrolled_count   INTEGER NOT NULL DEFAULT 0 CHECK (enrolled_count >= 0),
    rejected_count   INTEGER NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),

    submitted_by     VARCHAR(100) NOT NULL,
    submitted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_by      VARCHAR(100),
    accepted_at      TIMESTAMPTZ,

    -- Two people, and they must be different. One user who can upload a file and then
    -- accept it has an audit trail and no control. Spec §2.10, and this platform already
    -- carries one open finding of exactly that shape on pricing.
    CONSTRAINT chk_enrolment_submission_two_person CHECK (
        accepted_by IS NULL OR accepted_by <> submitted_by),

    -- An ACCEPTED row that cannot say who accepted it or when is an audit record with the
    -- audit removed.
    CONSTRAINT chk_enrolment_submission_accepted_complete CHECK (
        status <> 'ACCEPTED' OR (accepted_by IS NOT NULL AND accepted_at IS NOT NULL))
);

-- One file in flight per scheme. Two concurrent submissions can enrol the same loan
-- twice, and propose-then-accept widens the window between reading the schedule and
-- writing to it. Partial on PENDING, so accepted history never blocks next month's file.
CREATE UNIQUE INDEX ux_enrolment_submission_in_flight
    ON policy.enrolment_submission (policy_number)
    WHERE status = 'PENDING';

CREATE INDEX idx_enrolment_submission_scheme
    ON policy.enrolment_submission (tenant_id, policy_number, submitted_at DESC);

CREATE TABLE policy.enrolment_submission_row (
    submission_row_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    submission_id       UUID NOT NULL REFERENCES policy.enrolment_submission(submission_id),

    -- The line in the lender's OWN file, so the report can be read beside the spreadsheet
    -- that produced it. Line 1 is the header; data starts at 2.
    line_number         INTEGER NOT NULL CHECK (line_number > 1),

    loan_account_number VARCHAR(50),
    borrower_full_name  VARCHAR(200),

    outcome             VARCHAR(20) NOT NULL
        CHECK (outcome IN ('ENROLLED','ENROLLED_CAPPED','REJECTED')),
    reason_code         VARCHAR(40),
    reason              VARCHAR(500),

    -- Written at acceptance, so a report can still answer "which member did this row
    -- become?" six months later.
    policy_member_id    UUID,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A rejection with no reason is a row nobody can act on, and the reason is the entire
    -- product of this feature.
    CONSTRAINT chk_enrolment_row_rejection_has_reason CHECK (
        outcome <> 'REJECTED' OR (reason_code IS NOT NULL AND reason IS NOT NULL)),

    UNIQUE (submission_id, line_number)
);

CREATE INDEX idx_enrolment_row_submission
    ON policy.enrolment_submission_row (tenant_id, submission_id, line_number);

-- RLS in the post-V12 form. NULLIF, not a bare cast: TenantAwareDataSource RESETs the GUC
-- on a pooled connection borrowed without a tenant, Postgres returns the empty string,
-- and ''::uuid RAISES rather than filtering. No tenant must mean no rows.
--
-- ENABLE without FORCE, matching V9's four tables: Testcontainers connects as the owning
-- superuser, and FORCE here would change what those tests see relative to every other
-- table in this schema.
ALTER TABLE policy.enrolment_submission ENABLE ROW LEVEL SECURITY;
CREATE POLICY enrolment_submission_tenant_isolation ON policy.enrolment_submission
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE policy.enrolment_submission_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY enrolment_submission_row_tenant_isolation ON policy.enrolment_submission_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON policy.enrolment_submission TO app_role;
GRANT SELECT, INSERT, UPDATE ON policy.enrolment_submission_row TO app_role;
