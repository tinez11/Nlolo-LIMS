-- WHICH exclusion a decline invoked, and the dates it was computed from.
--
-- A decline that cannot say which window it used, measured from when, is a decline nobody can
-- defend. These claims are disputed by a commercial counterparty with the loan agreement in
-- front of them, sometimes years later, and "the assessor believed it was suicide" is not an
-- answer -- "the death was on 2027-02-03, cover started 2026-08-03, and the suicide exclusion
-- ran twelve months" is.

ALTER TABLE claims.claim
    ADD COLUMN decline_reason VARCHAR(40),
    ADD COLUMN exclusion_cover_start DATE,
    ADD COLUMN exclusion_window_months INTEGER;

-- All three together or none. A reason with no dates cannot be checked; dates with no reason
-- name nothing.
ALTER TABLE claims.claim
    ADD CONSTRAINT chk_claim_decline_reason_complete CHECK (
        (decline_reason IS NULL AND exclusion_cover_start IS NULL AND exclusion_window_months IS NULL)
     OR (decline_reason IS NOT NULL AND exclusion_cover_start IS NOT NULL
         AND exclusion_window_months IS NOT NULL));

-- Spelled out rather than left to the Java enum. A value that reached this column from a
-- future caller and matched nothing downstream would be a decline reason no report could
-- classify.
ALTER TABLE claims.claim
    ADD CONSTRAINT chk_claim_decline_reason_known CHECK (
        decline_reason IS NULL OR decline_reason IN
            ('SUICIDE_WITHIN_EXCLUSION', 'PRE_EXISTING_WITHIN_EXCLUSION'));

-- An exclusion decline is a REJECTION. Recording one against a claim that was approved would
-- be a contradiction the settlement report could not render.
ALTER TABLE claims.claim
    ADD CONSTRAINT chk_claim_decline_reason_only_when_rejected CHECK (
        decline_reason IS NULL OR status = 'REJECTED');
