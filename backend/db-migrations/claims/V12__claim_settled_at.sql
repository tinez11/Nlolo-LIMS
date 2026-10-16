-- db-migrations/claims/V12__claim_settled_at.sql
-- When a claim settled, recorded at the moment it does.
--
-- THE GAP THIS CLOSES. claim carried created_at, updated_at and date_of_event, and nothing for the one date a
-- finance officer and a claims manager both ask about: when the benefit was actually paid. "Claims settled this
-- month" and "benefits paid this month" were unanswerable, which is why the role dashboards (2026-10-14) drew both
-- cards dashed.
--
-- NOT updated_at. That moves whenever anything about the claim changes -- a reopened claim corrected in December
-- would report itself as settled in December although the money left in August.
--
-- NO BACKFILL. The claim table never recorded it, so the honest value for every existing settled claim is NULL, and
-- the console says "not recorded". claims.ClaimSettled events in the audit journal carry settledAt and could fill
-- some rows; that is a data-repair exercise with its own proof of correctness, not a column default. Deliberately
-- left for whoever needs the history, and recorded here so the absence is a decision rather than an oversight.
--
-- V12, not V10: the phase 2 plan was written against an older tree and called this V10, but V10__funeral_claims.sql
-- and V11__claim_document_request.sql already exist.
ALTER TABLE claims.claim
    ADD COLUMN settled_at TIMESTAMPTZ;

COMMENT ON COLUMN claims.claim.settled_at IS
    'When this claim settled, set by Claim.markSettled and Claim.settleWithNothingToPay. NULL while unsettled, and '
    'NULL for every claim settled before V12.';
