-- db-migrations/underwriting/V23__return_for_amendment.sql
-- Returned to the agent for amendment (2026-10-20, the agents' prospect-to-proposal flow).
--
-- RETURNED: the underwriter sent the proposal back with what is wrong and what to change; the agent amends and
-- resubmits, which reopens the SAME case (user's decision) -- the case's history, not a chain of cases, is the
-- record of each round.

ALTER TABLE underwriting.underwriting_case DROP CONSTRAINT underwriting_case_status_check;
ALTER TABLE underwriting.underwriting_case ADD CONSTRAINT underwriting_case_status_check
    CHECK (status IN ('OPEN', 'IN_REVIEW', 'DECIDED', 'RETURNED'));

-- Every return, amendment and resubmission on a case, oldest first. Append-only: what was asked and what changed
-- is the record the underwriter reads, and a history that can be edited is not one.
--
-- reasons (RETURNED): [{category, problem, change}] -- the category from a fixed list, the two texts the underwriter's
--   own words. changes (AMENDED): [{field, before, after}]. previous_decision (RESUBMITTED after a postponement): the
--   outcome and reason the resubmission answers, kept because the next decision overwrites the case's own columns.
-- round: which submission this belongs to -- 1 is the first, each resubmission starts the next.
CREATE TABLE underwriting.case_event (
    event_id           UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    case_id            UUID NOT NULL REFERENCES underwriting.underwriting_case (case_id),
    kind               VARCHAR(20) NOT NULL CHECK (kind IN ('RETURNED', 'AMENDED', 'RESUBMITTED')),
    round              INTEGER NOT NULL CHECK (round >= 1),
    actor_subject      VARCHAR(255) NOT NULL,
    reasons            JSONB,
    changes            JSONB,
    previous_decision  JSONB,
    note               TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT case_event_returned_has_reasons CHECK (kind <> 'RETURNED' OR reasons IS NOT NULL),
    CONSTRAINT case_event_amended_has_changes CHECK (kind <> 'AMENDED' OR changes IS NOT NULL)
);

CREATE INDEX case_event_case ON underwriting.case_event (tenant_id, case_id, created_at);

ALTER TABLE underwriting.case_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY case_event_tenant_isolation ON underwriting.case_event
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON underwriting.case_event TO app_role;
