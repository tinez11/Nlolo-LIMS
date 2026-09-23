-- The one fact that decides whether a policy may be reinsured at all, stored where BOTH
-- listeners can read it.
--
-- reinsurance holds group schemes out of cession deliberately: a scheme's sum assured is the
-- total of a member schedule, not one life, and the treaty model cannot express classes of
-- business, per-scheme provisions, or a cession that follows a declining insured amount (design
-- spec 0a, answered by the client 2026-09-22). PolicyEventListener enforces that -- but only on
-- the CESSION side, and only after it has already written the projection row unconditionally.
--
-- That left a second door open. ClaimEventListener treats "no projection row" as its signal that
-- a policy was never in scope, and a group scheme HAS one; so a settled scheme claim fell past
-- that check to the XOL path, which needs no cession at all and recovers the excess of the loss
-- over retention. A credit-life claim would have recovered against a treaty nobody ever agreed
-- covered it, and finaccounting would have booked the recoverable as a real asset.
--
-- Unreachable until now, which is why it survived: before the credit-life claim chain existed no
-- scheme claim could settle. It is reachable today.
--
-- Nullable, because every row written before this migration predates the column and there is no
-- honest value to backfill: this module never recorded a policy's category, and guessing
-- INDIVIDUAL for historical rows would silently re-open the door for any scheme already
-- projected. The listener therefore treats NULL as "not known to be a scheme" -- the behaviour
-- those rows already had -- and only a row that positively says GROUP_LIFE or CREDIT_LIFE is
-- held back. New rows always carry it, because policy.PolicyActivated has carried
-- productCategory since the credit-life build.
ALTER TABLE reinsurance.policy_projection
    ADD COLUMN product_category VARCHAR(30);

COMMENT ON COLUMN reinsurance.policy_projection.product_category IS
    'The policy''s product category as policy.PolicyActivated reported it. GROUP_LIFE and '
    'CREDIT_LIFE are never ceded and never recovered against -- see this migration''s header. '
    'NULL means the row predates this column, not that the policy is individual.';
