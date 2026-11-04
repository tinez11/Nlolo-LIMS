-- db-migrations/policy/V45__acted_alone.sql
-- Acting alone (2026-11-04, the user: a company with one person in a department must still finish its work). A person
-- given schemes.enrolments-own may accept a credit-life file they uploaded; one given policies.surrender-approve-own a
-- surrender they requested. The row says so in acted_alone, and the two-person rule holds for everyone else.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing. IF EXISTS: a test that loads only part of
-- the module has not every table, and the tables it has still take the rule.

ALTER TABLE IF EXISTS policy.enrolment_submission ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS policy.enrolment_submission DROP CONSTRAINT IF EXISTS chk_enrolment_submission_two_person;
ALTER TABLE IF EXISTS policy.enrolment_submission
    ADD CONSTRAINT chk_enrolment_submission_two_person
    CHECK (accepted_by IS NULL OR accepted_by <> submitted_by OR acted_alone);

ALTER TABLE IF EXISTS policy.exit_submission ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS policy.exit_submission DROP CONSTRAINT IF EXISTS chk_exit_submission_two_person;
ALTER TABLE IF EXISTS policy.exit_submission
    ADD CONSTRAINT chk_exit_submission_two_person
    CHECK (accepted_by IS NULL OR accepted_by <> submitted_by OR acted_alone);

ALTER TABLE IF EXISTS policy.surrender_request ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS policy.surrender_request DROP CONSTRAINT IF EXISTS surrender_two_person;
ALTER TABLE IF EXISTS policy.surrender_request
    ADD CONSTRAINT surrender_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by OR acted_alone);
