-- db-migrations/underwriting/V10__issuance_failure.sql
-- An accepted case that issued nothing must say so on its own record.
--
-- WHAT THIS FIXES, and it is not hypothetical. A case was decided ACCEPT. Automatic issuance
-- computed a premium of 0.00 (the product's AGE band carried a 0.0000 multiplier) and the insert
-- was refused by policy's chk_premium_amount_positive. Because issuance runs in an AFTER_COMMIT
-- listener, the decision had already committed: the case stayed ACCEPT, no policy row was ever
-- created, the exception went to a log file nobody was reading, and the underwriter had every
-- reason to believe a policy existed. It was found days later, by accident.
--
-- The listener's own comment admitted the gap -- "only automatic issuance needs a manual retry
-- (via /policies/manual-issue) if this path fails. No dead-letter queue is built for this
-- listener specifically in M3" -- but a retry nobody is told to perform is not a recovery path.
--
-- WHY ON THE CASE, rather than a queue or a table of its own. The question this answers is "did
-- my acceptance actually produce a policy", and the place a person asks it is the case they just
-- decided. A separate failure table would be correct and would be looked at by nobody. The case
-- is already on screen, already in the queue, and already the thing under discussion.
--
-- WHY A REASON AND NOT A FLAG. "Issuance failed" sends somebody to a log file, which is where
-- this defect already spent its life. The message names what was refused -- the premium, the
-- constraint, the band -- so the person reading it can tell a product misconfiguration from an
-- outage and knows whether retrying will do anything.
--
-- CLEARED, not just set: a case that failed and then issued successfully must stop claiming it
-- failed, or the field becomes noise that gets ignored the way the log did.
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN issuance_failure_reason TEXT,
    ADD COLUMN issuance_failed_at      TIMESTAMPTZ;

COMMENT ON COLUMN underwriting.underwriting_case.issuance_failure_reason IS
    'Why automatic policy issuance failed for this decided case, verbatim from the exception. '
    'NULL means either that issuance succeeded or that it was never attempted -- the two are '
    'distinguished by the decision outcome, since only ACCEPT and LOADED attempt issuance.';

COMMENT ON COLUMN underwriting.underwriting_case.issuance_failed_at IS
    'When that failure was recorded. Set and cleared together with issuance_failure_reason.';

-- Both columns move together or not at all. A reason with no timestamp reads as a failure that
-- happened at no particular time, and a timestamp with no reason is the log-file problem again.
ALTER TABLE underwriting.underwriting_case
    ADD CONSTRAINT underwriting_case_issuance_failure_shape CHECK (
        (issuance_failure_reason IS NULL AND issuance_failed_at IS NULL)
        OR (issuance_failure_reason IS NOT NULL AND issuance_failed_at IS NOT NULL)
    );

-- "Show me every acceptance that never became a policy" is the sweep this exists to make
-- possible, and it is a small set by design -- partial, so it stays small.
CREATE INDEX idx_underwriting_case_issuance_failed
    ON underwriting.underwriting_case (tenant_id, issuance_failed_at DESC)
    WHERE issuance_failure_reason IS NOT NULL;
