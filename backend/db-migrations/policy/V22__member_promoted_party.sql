-- When a name on a lender's spreadsheet became a real person.
--
-- A credit-life member is enrolled FREEFORM, because no lender sends a national ID and party
-- de-duplication cannot fire without one (spec 2.2). That is correct for four hundred rows a
-- month, and it stops being correct at exactly one moment: the claim. The platform is about to
-- pay out against this person, and "who died" cannot be a string in a spreadsheet cell.
--
-- The timestamp is kept because the promotion is an ACT someone performed, on a claim somebody
-- may dispute years later. member_type alone would say a member is a party today without
-- saying they were ever anything else.

ALTER TABLE policy.policy_member
    ADD COLUMN promoted_to_party_at TIMESTAMPTZ,
    ADD COLUMN promoted_by VARCHAR(100);

ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_promotion_complete
        CHECK ((promoted_to_party_at IS NULL) = (promoted_by IS NULL));

-- A promotion only ever runs FREEFORM -> PARTY, so a promoted member necessarily has a party.
ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_promoted_has_party
        CHECK (promoted_to_party_at IS NULL OR member_party_id IS NOT NULL);

-- THE NAME THE LENDER USED SURVIVES PROMOTION, and this is what makes that a rule rather than
-- an accident of the implementation.
--
-- V13's chk_policy_member_exactly_one_designation says a PARTY member carries a party id and
-- NO name and NO date of birth, while a FREEFORM member carries a name and no party. That is
-- right at enrolment: a member is designated EITHER by a party OR by a string, never both.
--
-- A promoted member is the one legitimate exception. They are a PARTY -- that is the point of
-- promoting them -- and they must still carry the name and date of birth the lender's file
-- used, because that file is the only document the lender has and it will keep arriving every
-- month calling them the same thing. A promoted borrower who answers only to a party id cannot
-- be reconciled against the spreadsheet that enrolled them.
--
-- So the rule becomes: PARTY with no name, FREEFORM with a name, or PROMOTED with both.
ALTER TABLE policy.policy_member
    DROP CONSTRAINT chk_policy_member_exactly_one_designation;

ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_exactly_one_designation CHECK (
        (member_type = 'PARTY'    AND member_party_id IS NOT NULL
                                  AND promoted_to_party_at IS NOT NULL
                                  AND member_name IS NOT NULL)
     OR (member_type = 'PARTY'    AND member_party_id IS NOT NULL
                                  AND promoted_to_party_at IS NULL
                                  AND member_name IS NULL AND member_date_of_birth IS NULL)
     OR (member_type = 'FREEFORM' AND member_party_id IS NULL
                                  AND member_name IS NOT NULL)
    );
