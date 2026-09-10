-- db-migrations/underwriting/V9__group_proposal.sql
-- A group scheme, before it is a contract.
--
-- Group business bypassed underwriting entirely. POST /group-schemes created the policy, the
-- scheme and every member in one call, ACTIVE on return -- no case, no assessment, no
-- decision, and (until the role gate) any staff token at all. Meanwhile individual business
-- had a queue, a person's decision, a senior gate on departing from advice, and an offer the
-- customer accepts by paying. Two flows on one platform, and the one insuring five hundred
-- people at a time was the unsupervised one.
--
-- So a scheme is proposed as a case first. What had nowhere to live is here: the terms, the
-- grade table, and the schedule of lives being asked for.
--
-- MODELLED ON proposal_beneficiary, deliberately and not by coincidence. That table holds
-- nominations taken on the proposal and issuance copies them into policy.beneficiary, "the two
-- deliberately identical in shape". This is the same idea one size up, and the same rule
-- applies: underwriting holds what was ASKED FOR, policy holds what was GRANTED, and neither
-- reads the other's tables.
CREATE TABLE underwriting.proposal_group_scheme (
    -- 1:1 with the case, so the case id IS the key. A row here is the statement "this case is
    -- a scheme", which is why nothing else needs a discriminator column.
    case_id             UUID PRIMARY KEY REFERENCES underwriting.underwriting_case(case_id),
    tenant_id           UUID NOT NULL,

    benefit_basis       VARCHAR(20) NOT NULL CHECK (benefit_basis IN ('FLAT','SALARY_MULTIPLE','GRADED')),
    flat_benefit_amount NUMERIC(19,2) CHECK (flat_benefit_amount IS NULL OR flat_benefit_amount > 0),
    salary_multiple     NUMERIC(6,2)  CHECK (salary_multiple IS NULL OR salary_multiple > 0),
    fcl_amount          NUMERIC(19,2) CHECK (fcl_amount IS NULL OR fcl_amount > 0),
    currency            CHAR(3) NOT NULL,

    -- The premium AGREED with the employer, not a computed one. A scheme is not priced by the
    -- individual formula: that prices one life from one age band, and the age it would read is
    -- the employer's -- a company, insuring nobody. Carried verbatim to issuance.
    premium_amount      NUMERIC(19,2) NOT NULL CHECK (premium_amount > 0),
    premium_currency    CHAR(3) NOT NULL,
    premium_frequency   VARCHAR(20) NOT NULL,

    commencement_date   DATE,
    policy_term_months  INTEGER CHECK (policy_term_months IS NULL OR policy_term_months > 0),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),

    -- The basis and its parameter must agree, exactly as policy.group_scheme's own
    -- group_scheme_basis_parameter_present does. A SALARY_MULTIPLE proposal with no multiple
    -- cannot value anybody; a FLAT one carrying a multiple is telling two stories.
    CONSTRAINT proposal_group_basis_parameter_present CHECK (
        (benefit_basis = 'FLAT'            AND flat_benefit_amount IS NOT NULL AND salary_multiple IS NULL)
     OR (benefit_basis = 'SALARY_MULTIPLE' AND salary_multiple     IS NOT NULL AND flat_benefit_amount IS NULL)
     OR (benefit_basis = 'GRADED'          AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL)
    )
);

CREATE TABLE underwriting.proposal_group_grade (
    proposal_group_grade_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    case_id             UUID NOT NULL REFERENCES underwriting.proposal_group_scheme(case_id),
    grade_code          VARCHAR(30) NOT NULL,
    benefit_amount      NUMERIC(19,2) NOT NULL CHECK (benefit_amount > 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (case_id, grade_code)
);

CREATE TABLE underwriting.proposal_group_member (
    proposal_group_member_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    case_id             UUID NOT NULL REFERENCES underwriting.proposal_group_scheme(case_id),
    member_party_id     UUID NOT NULL,
    grade_code          VARCHAR(30),
    salary_amount       NUMERIC(19,2) CHECK (salary_amount IS NULL OR salary_amount > 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One proposal line per person. A schedule naming somebody twice is an employer's
    -- spreadsheet error, and admitting it would double both their cover and the scheme total.
    UNIQUE (case_id, member_party_id)
);
CREATE INDEX idx_proposal_group_member_case ON underwriting.proposal_group_member (case_id);

-- No joined_on column, unlike policy.policy_member. Every life on an OPENING schedule joins
-- when the scheme commences -- that is what an opening schedule means -- and a per-line date
-- here would be a second answer to a question commencement_date already settles. Members who
-- join later are admitted against the issued scheme, not against the proposal.

ALTER TABLE underwriting.proposal_group_scheme ENABLE ROW LEVEL SECURITY;
CREATE POLICY proposal_group_scheme_tenant_isolation ON underwriting.proposal_group_scheme
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE underwriting.proposal_group_grade ENABLE ROW LEVEL SECURITY;
CREATE POLICY proposal_group_grade_tenant_isolation ON underwriting.proposal_group_grade
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE underwriting.proposal_group_member ENABLE ROW LEVEL SECURITY;
CREATE POLICY proposal_group_member_tenant_isolation ON underwriting.proposal_group_member
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- NULLIF from the start, not left for a later sweep. Without it a tenant-less query RAISES
-- ("invalid input syntax for type uuid") instead of returning nothing, because current_setting
-- on a RESET GUC returns the empty string rather than NULL. Found in production and swept
-- across all 91 existing policies on 2026-09-10; no new policy should need sweeping again.
GRANT SELECT, INSERT, UPDATE, DELETE ON underwriting.proposal_group_scheme TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON underwriting.proposal_group_grade TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON underwriting.proposal_group_member TO app_role;

COMMENT ON TABLE underwriting.proposal_group_scheme IS
    'A group scheme as asked for. Copied into policy.group_scheme at issuance; the two are '
    'deliberately similar in shape, and neither module reads the other''s table.';
