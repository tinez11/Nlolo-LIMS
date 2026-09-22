-- A lender's monthly exits file, as submitted and as judged.
--
-- The other half of the enrolment file. Monthly files add borrowers and say nothing about
-- loans that ended, so without this channel no refund and no clawback can ever fire, and a
-- repaid borrower stays insured for a debt that no longer exists (spec 2.12). Client answer
-- 3.5, 2026-09-22: monthly.
--
-- Structurally a copy of V15, deliberately. Cancelling cover in bulk carries the same risk as
-- writing it -- arguably more, because the failure is silent: nobody complains that they were
-- taken off cover they had already stopped paying for. So it gets the same controls, and a
-- reader who understands one file understands the other.

CREATE TABLE policy.exit_submission (
    submission_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    policy_number    VARCHAR(20) NOT NULL REFERENCES policy.group_scheme(policy_number),

    -- The lender's own file, byte for byte. When a lender disputes whether a borrower was
    -- declared as exited -- and they will, because an exit is what triggers their refund --
    -- the answer is what they sent, not our reading of it.
    document_ref     VARCHAR(255) NOT NULL,
    file_name        VARCHAR(255),

    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','WITHDRAWN')),

    row_count        INTEGER NOT NULL CHECK (row_count >= 0),
    exited_count     INTEGER NOT NULL DEFAULT 0 CHECK (exited_count >= 0),
    rejected_count   INTEGER NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),

    submitted_by     VARCHAR(100) NOT NULL,
    submitted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_by      VARCHAR(100),
    accepted_at      TIMESTAMPTZ,

    -- Two people, and they must be different. Identical reasoning to the enrolment file: one
    -- user who can upload a file and then accept it has an audit trail and no control.
    CONSTRAINT chk_exit_submission_two_person CHECK (
        accepted_by IS NULL OR accepted_by <> submitted_by),

    CONSTRAINT chk_exit_submission_accepted_complete CHECK (
        status <> 'ACCEPTED' OR (accepted_by IS NOT NULL AND accepted_at IS NOT NULL))
);

-- One exits file in flight per scheme, for the reason the enrolment file has one: two
-- concurrent files can exit the same loan twice, and propose-then-accept widens the window
-- between reading the roll and writing to it.
--
-- Note this does NOT block an enrolment file and an exits file being in flight together. They
-- are different indexes on different tables and that is correct: a lender sending this
-- month's joiners and this month's leavers at the same time is the ordinary case, and the two
-- cannot collide because a row can only be enrolled OR exited, never both.
CREATE UNIQUE INDEX ux_exit_submission_in_flight
    ON policy.exit_submission (policy_number)
    WHERE status = 'PENDING';

CREATE INDEX idx_exit_submission_scheme
    ON policy.exit_submission (tenant_id, policy_number, submitted_at DESC);

CREATE TABLE policy.exit_submission_row (
    submission_row_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    submission_id       UUID NOT NULL REFERENCES policy.exit_submission(submission_id),

    -- The line in the lender's OWN file. Line 1 is the header; data starts at 2.
    line_number         INTEGER NOT NULL CHECK (line_number > 1),

    -- The reference WE issued and the report gave back to them (V16). Unlike the enrolment
    -- file there is no blank case: an exits file is by definition about loans we already
    -- cover, so a row with no reference names nobody.
    member_reference    VARCHAR(30),

    -- The exit AS JUDGED, carried on the row rather than re-read from the stored file at
    -- acceptance. Re-parsing would risk the roll and the report disagreeing about what was
    -- approved, and it is the judged values a second person is being asked to accept.
    exit_date           DATE,
    exit_reason         VARCHAR(30),

    -- The lender's own figure. Recorded, never trusted: our declining schedule is what values
    -- a claim, and keeping theirs is how a systematic divergence shows up as data rather than
    -- as an argument.
    outstanding_balance_at_exit NUMERIC(19,2)
        CHECK (outstanding_balance_at_exit IS NULL OR outstanding_balance_at_exit >= 0),

    outcome             VARCHAR(20) NOT NULL
        CHECK (outcome IN ('EXITED','REJECTED')),
    reason_code         VARCHAR(40),
    reason              VARCHAR(500),

    -- A row that will end cover must carry everything needed to end it. Without this,
    -- acceptance could reach a row it cannot process half-way through a file, having already
    -- taken earlier borrowers off risk.
    CONSTRAINT chk_exit_row_actionable_is_complete CHECK (
        outcome = 'REJECTED'
        OR (member_reference IS NOT NULL AND exit_date IS NOT NULL
            AND exit_reason IS NOT NULL)),

    -- CLAIM_SETTLED is absent on purpose. A lender cannot assert that the insurer paid out;
    -- that reason is written only by dischargeForSettledClaim. Allowing it here would suppress
    -- the refund and the clawback on a loan that was merely repaid.
    CONSTRAINT chk_exit_row_reason_known CHECK (
        exit_reason IS NULL OR exit_reason IN
            ('SETTLED_EARLY', 'REFINANCED', 'WRITTEN_OFF', 'CANCELLED')),

    -- Written at acceptance, so a report can still answer "which member did this row take
    -- off cover?" months later.
    policy_member_id    UUID,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A rejection with no reason is a row nobody can act on, and on this file the consequence
    -- runs the other way from enrolment: an unexplained rejection means a lender believes a
    -- loan is off cover when it is still on it, and still being charged for.
    CONSTRAINT chk_exit_row_rejection_has_reason CHECK (
        outcome <> 'REJECTED' OR (reason_code IS NOT NULL AND reason IS NOT NULL)),

    UNIQUE (submission_id, line_number)
);

CREATE INDEX idx_exit_row_submission
    ON policy.exit_submission_row (tenant_id, submission_id, line_number);

-- RLS in the post-V12 form. NULLIF, not a bare cast: TenantAwareDataSource RESETs the GUC on
-- a pooled connection borrowed without a tenant, Postgres returns the empty string, and
-- ''::uuid RAISES rather than filtering. No tenant must mean no rows.
--
-- ENABLE without FORCE, matching V9 and V15: Testcontainers connects as the owning superuser,
-- and FORCE here would change what those tests see relative to every other table in the schema.
ALTER TABLE policy.exit_submission ENABLE ROW LEVEL SECURITY;
CREATE POLICY exit_submission_tenant_isolation ON policy.exit_submission
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE policy.exit_submission_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY exit_submission_row_tenant_isolation ON policy.exit_submission_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON policy.exit_submission TO app_role;
GRANT SELECT, INSERT, UPDATE ON policy.exit_submission_row TO app_role;
