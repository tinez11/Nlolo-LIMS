-- db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql
-- What the applicant actually states on a proposal form.
--
-- Until now a case recorded the risk (who, which product, how much cover) and nothing about
-- the CONTRACT the applicant asked for. Term, premium-paying term, payment frequency and
-- beneficiary nominations existed only on POST /policies/manual-issue -- so manual issue was
-- the only screen on this platform that could produce a complete policy, and a policy issued
-- on the normal path came out with no term, no maturity date (it is derived from commencement
-- plus term) and nobody nominated.
--
-- That is very likely why staff reached for manual issue. It is not a shortcut; it is the only
-- screen that captures the whole contract.
--
-- ALL OPTIONAL. A product that does not term -- whole life, an annuity, an annually renewable
-- group scheme -- genuinely has no term, and a proposal arriving with the nomination blank is
-- ordinary rather than incomplete. The service enforces the conditional rules the columns
-- cannot: a term must fall inside the product version's eligibility bounds, and shares must
-- total 100 if any nomination is given at all.
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN requested_term_months       INTEGER CHECK (requested_term_months > 0),
    ADD COLUMN premium_paying_term_months  INTEGER CHECK (premium_paying_term_months > 0),
    ADD COLUMN premium_frequency           VARCHAR(10)
        CHECK (premium_frequency IN ('MONTHLY','QUARTERLY','ANNUALLY')),
    -- Mirrors policy_premium_paying_term_within_term (policy V6): premiums may be paid for a
    -- shorter time than cover runs (a limited-payment policy), never for longer. Checked here
    -- so a proposal cannot record a shape the policy would later refuse to be issued as.
    ADD CONSTRAINT chk_proposal_paying_term_within_term CHECK (
        premium_paying_term_months IS NULL
        OR requested_term_months IS NULL
        OR premium_paying_term_months <= requested_term_months
    );

COMMENT ON COLUMN underwriting.underwriting_case.requested_term_months IS
    'The term the applicant asked for. Validated against the product version eligibility bounds at openCase -- those bounds were previously only ever checked on the manual issue form, so the normal path accepted a term the product may not permit.';

-- Nominations as taken on the proposal, before any policy exists.
--
-- Its own table in this schema rather than a row in policy.beneficiary, for the plain reason
-- that policy.beneficiary is keyed by policy_number and there is no policy yet -- and no
-- cross-schema write is permitted in either direction. The shape mirrors it exactly so the
-- issuance listener can map one to the other without reinterpreting anything, and so a reader
-- comparing the two sees the same columns saying the same things.
CREATE TABLE underwriting.proposal_beneficiary (
    proposal_beneficiary_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    case_id             UUID NOT NULL REFERENCES underwriting.underwriting_case(case_id),
    beneficiary_type    VARCHAR(10) NOT NULL CHECK (beneficiary_type IN ('PARTY','FREEFORM')),
    party_id            UUID,               -- required iff type = PARTY
    freeform_designee   VARCHAR(255),       -- required iff type = FREEFORM
    share_percent       NUMERIC(5,2) NOT NULL CHECK (share_percent >= 0 AND share_percent <= 100),
    revocable           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_proposal_beneficiary_exactly_one_designation CHECK (
        (beneficiary_type = 'PARTY' AND party_id IS NOT NULL AND freeform_designee IS NULL)
        OR (beneficiary_type = 'FREEFORM' AND freeform_designee IS NOT NULL AND party_id IS NULL)
    )
);
CREATE INDEX idx_proposal_beneficiary_case ON underwriting.proposal_beneficiary (case_id);

COMMENT ON TABLE underwriting.proposal_beneficiary IS
    'Beneficiary nominations as taken on the proposal. Copied into policy.beneficiary at issuance; the two are deliberately identical in shape.';
