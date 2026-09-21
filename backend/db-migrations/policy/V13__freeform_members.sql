-- A member may be named without being registered as a party.
--
-- Specified in plans/2026-09-10-group-scheme-substitution-and-notices.md and built here
-- because credit life needs it first: 400 borrowers per monthly file would otherwise be
-- 400 kyc_status='PENDING' rows in the staff Clients queue, for people the insurer has no
-- reason to identify unless one of them dies. A borrower is promoted to a real party at
-- claim, when there is a death certificate and an identity to verify anyway.
--
-- The same reasoning already applied to employer schemes, which cover dependants -- a
-- spouse, a child -- who have no KYC and never will. Group premium is not individually
-- rated, so a name plus the scheme's basis values a member completely.
--
-- See docs/superpowers/specs/2026-09-21-credit-life-design.md §2.2.

ALTER TABLE policy.policy_member
    ADD COLUMN member_type          VARCHAR(10) NOT NULL DEFAULT 'PARTY'
        CHECK (member_type IN ('PARTY','FREEFORM')),
    ADD COLUMN member_name          VARCHAR(200),
    ADD COLUMN member_date_of_birth DATE;

-- Every pre-existing row is a PARTY member and the default above has already said so.
-- Drop it now so a new row must state its type rather than inherit a guess.
ALTER TABLE policy.policy_member ALTER COLUMN member_type DROP DEFAULT;

ALTER TABLE policy.policy_member ALTER COLUMN member_party_id DROP NOT NULL;

-- One thing or the other, never both and never neither. Mirrors
-- chk_beneficiary_exactly_one_designation, which solved the identical problem for
-- beneficiaries: a row naming a party AND a loose name is telling two stories about who
-- is covered, and a claims assessor cannot be asked to guess which.
ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_exactly_one_designation CHECK (
        (member_type = 'PARTY'    AND member_party_id IS NOT NULL
                                  AND member_name IS NULL AND member_date_of_birth IS NULL)
     OR (member_type = 'FREEFORM' AND member_party_id IS NULL
                                  AND member_name IS NOT NULL)
    );

-- ux_policy_member_active was keyed on member_party_id, which is now nullable -- and in
-- Postgres a NULL never collides with anything, so 400 freeform members would all "pass"
-- a uniqueness check that had quietly stopped checking. Replaced with a partial index
-- that only guards the designation it can actually see.
DROP INDEX IF EXISTS policy.ux_policy_member_active;

CREATE UNIQUE INDEX ux_policy_member_active_party
    ON policy.policy_member (policy_number, member_party_id)
    WHERE status = 'ACTIVE' AND member_party_id IS NOT NULL;

-- Freeform members have no unique identity of their own, and must not be given a
-- fabricated one: a scheme legitimately covers a father and a son of the same name, and
-- a household of dependants may share both a surname and a birth year. Credit life gives
-- its members a real key in V14 (the loan account number) and enforces it there. This
-- index is for searching the roll by name, not for uniqueness.
CREATE INDEX idx_policy_member_freeform_name
    ON policy.policy_member (tenant_id, policy_number, member_name)
    WHERE status = 'ACTIVE' AND member_name IS NOT NULL;
