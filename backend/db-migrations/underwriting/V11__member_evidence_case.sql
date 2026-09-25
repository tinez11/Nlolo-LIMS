-- db-migrations/underwriting/V11__member_evidence_case.sql
-- Say, on the case itself, when it is a scheme member's evidence case rather than a proposal.
--
-- THE DEFECT THIS CLOSES. A member whose benefit exceeds the scheme's free cover limit is covered
-- up to it and referred for evidence -- policy opens an individual case for the excess. Nothing
-- on that case said what it was. In the queue it read as an ordinary new proposal on a group
-- product, and when an underwriter accepted it the decision listener treated it as one: it issued
-- the MEMBER a separate single-life policy on the scheme's product, while the member's own record
-- stayed EVIDENCE_REQUIRED and the excess was never granted. A decline recorded nothing at all.
--
-- The link could not be left to policy.policy_member.underwriting_case_id alone: raising the
-- limit above a member's benefit clears that column and leaves the case open, so the one place
-- that knew whose evidence it was forgot.
--
-- Both columns or neither: an evidence case is FOR one member of one scheme. Opaque references,
-- as agent_of_record_id is -- underwriting may not depend on policy, so no foreign key.
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN evidence_for_policy_number VARCHAR(20),
    ADD COLUMN evidence_for_member_id     UUID,
    ADD CONSTRAINT chk_evidence_for_both_or_neither CHECK (
        (evidence_for_policy_number IS NULL) = (evidence_for_member_id IS NULL));

COMMENT ON COLUMN underwriting.underwriting_case.evidence_for_policy_number IS
    'Set when this case is a scheme member''s free-cover-limit evidence case: the scheme it '
    'belongs to. Its decision grants or refuses that member''s excess; it never issues a policy.';
COMMENT ON COLUMN underwriting.underwriting_case.evidence_for_member_id IS
    'The policy_member the evidence is for. See evidence_for_policy_number.';
