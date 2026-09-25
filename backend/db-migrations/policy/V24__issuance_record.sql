-- db-migrations/policy/V24__issuance_record.sql
-- Keep what an issuance says about itself: why it went round the normal path, and who did it.
--
-- THE DEFECT THIS CLOSES. Manual issue and POST /group-schemes are the exception routes -- a
-- policy put on risk without the ordinary proposal, decision and first premium -- and both
-- REQUIRE the caller to say why: an issuance basis (MIGRATION, UNDERWRITING_OVERRIDE, ...) and
-- a free-text reason. Both were validated and then discarded. Neither reached a column, and
-- neither reached the policy.PolicyIssued event, so across every issuance in the audit log the
-- platform could not say which policies went round underwriting, or why, or on whose word.
-- A credit-life scheme -- set up by an underwriter from agreed terms, with no case -- was the
-- sharpest instance: nothing recorded who agreed them.
--
-- issued_by_name is the issuer's display name from their token, beside created_by (the subject,
-- what access compares). A name is what a compliance reader can act on; a subject is not.
--
-- All three nullable: an ordinary issuance on an underwriting decision has no basis and no
-- reason -- the case is its record -- and every policy issued before this column is unknown.
ALTER TABLE policy.policy
    ADD COLUMN issuance_basis  VARCHAR(30),
    ADD COLUMN issuance_reason TEXT,
    ADD COLUMN issued_by_name  VARCHAR(255);

COMMENT ON COLUMN policy.policy.issuance_basis IS
    'Why this policy was issued outside the ordinary proposal-decision-premium path (MIGRATION, '
    'CONVERSION, REINSTATEMENT, UNDERWRITING_OVERRIDE, ...). NULL for ordinary new business.';
COMMENT ON COLUMN policy.policy.issuance_reason IS
    'The issuer''s own words for an exception-path issuance. NULL for ordinary new business.';
COMMENT ON COLUMN policy.policy.issued_by_name IS
    'Display name of whoever issued it, from their token -- the underwriter of record for a '
    'scheme set up from agreed terms. NULL for automatic issuance and for policies before V24.';
