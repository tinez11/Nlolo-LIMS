-- Who the proposal is for, and how to refer to it out loud.
--
-- Two problems, one migration.
--
-- 1. A case is identified by a bare UUID. That is why the underwriting queue was
--    unreadable until parties were resolved to names, and it is still true of the case
--    itself: nobody can quote a case reference over the phone. proposal_number is the
--    human handle, shaped like policy_number (PRO-XXXXXXXX) for the same reason.
--
-- 2. **A proposal where the policyholder insures somebody else cannot be expressed at
--    all.** underwriting_case has one applicant_party_id and that is the whole party
--    model. Most life business is not self-insured -- a parent insures a child, a spouse
--    a spouse, an employer its staff -- and both of the still-unbuilt contexts REQUIRE
--    the distinction: group business is definitionally one policyholder and many lives
--    assured, and credit life is a lender-driven policy on a borrower's life.
--
-- See docs/superpowers/specs/2026-09-03-build4b-proposal-identity-design.md.

ALTER TABLE underwriting.underwriting_case
    ADD COLUMN proposal_number            VARCHAR(20),
    ADD COLUMN life_assured_party_id      UUID,
    ADD COLUMN branch                     VARCHAR(100),
    -- Deliberately free text, NOT a CHECK-constrained enum. The platform does not own
    -- this vocabulary: TIRA's return catalogue is still an open item (README standing
    -- item 6), and regreporting will eventually need whatever list the regulator
    -- publishes. Minting our own set here would make it the de facto schema in an
    -- append-only-ish column -- the same trap the endorsement `changes` JSONB and the M7
    -- commission semantics fell into. Constrain it once the list is ratified.
    ADD COLUMN source_of_business         VARCHAR(60),
    ADD COLUMN proposed_commencement_date DATE;

-- **This backfill copies a known fact; it does not invent one**, which is why it is here
-- when Build 1 and Build 2 both deliberately refused to backfill.
--
-- Sex, occupation and policy term were facts nobody had recorded, so any default would
-- have been fabrication. The life assured is different: it WAS recorded, as
-- applicant_party_id, because the model had exactly one party slot and that slot is
-- unambiguously the life whose mortality is rated -- ProductApiImpl derives entry age
-- from the applicant's date of birth, and RiskProfile is built from the applicant.
-- Copying it forward states what the old rows already meant.
UPDATE underwriting.underwriting_case
   SET life_assured_party_id = applicant_party_id
 WHERE life_assured_party_id IS NULL;

-- One proposal number per tenant. Partial, because rows predating this migration have
-- none and must not collide with each other.
CREATE UNIQUE INDEX ux_underwriting_case_proposal_number
    ON underwriting.underwriting_case (tenant_id, proposal_number)
    WHERE proposal_number IS NOT NULL;

-- "Every case naming this person as the life assured" -- the lookup a claims assessor
-- needs for contestability review, which today has no working surface precisely because
-- the declarations hang off a case reachable only when the claimant IS the life assured.
CREATE INDEX idx_underwriting_case_life_assured
    ON underwriting.underwriting_case (tenant_id, life_assured_party_id)
    WHERE life_assured_party_id IS NOT NULL;
