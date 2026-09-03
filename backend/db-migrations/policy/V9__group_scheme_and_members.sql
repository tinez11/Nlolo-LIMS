-- Group business: one master policy, many insured members.
--
-- Confirmed with the client on 2026-09-03: a member is NOT a separate policy. ABC Company
-- buys Group Life for 500 employees -- the company is the policyholder, and the 500
-- employees are the lives assured on one master policy.
--
--   policy.policy (GROUP_LIFE)         the master policy / scheme
--        |
--        +-- policy.group_scheme       benefit basis, FCL          (1:1)
--        +-- group_scheme_grade        grade -> benefit            (GRADED only)
--        +-- policy_member             who is covered
--                 +-- policy_member_benefit   effective-dated salary and benefit
--
-- See docs/superpowers/specs/2026-09-03-build5-group-business-design.md.

CREATE TABLE policy.group_scheme (
    -- 1:1 with the master policy rather than columns on policy.policy: 625 individual
    -- policies should not each carry six null group columns, and a row here IS the
    -- statement "this policy is a scheme".
    policy_number       VARCHAR(20) PRIMARY KEY REFERENCES policy.policy(policy_number),
    tenant_id           UUID NOT NULL,

    -- How each member's benefit is arrived at. One basis per scheme, configured rather
    -- than hard-coded, because real schemes differ: flat is typical for SACCO, funeral
    -- and credit-linked cover; salary multiple is the standard for formal employer
    -- schemes; graded splits by staff category.
    benefit_basis       VARCHAR(20) NOT NULL CHECK (benefit_basis IN ('FLAT','SALARY_MULTIPLE','GRADED')),

    -- Exactly one of these is meaningful, decided by benefit_basis. GRADED reads its
    -- amounts from group_scheme_grade instead.
    flat_benefit_amount NUMERIC(19,2) CHECK (flat_benefit_amount IS NULL OR flat_benefit_amount > 0),
    salary_multiple     NUMERIC(6,2)  CHECK (salary_multiple IS NULL OR salary_multiple > 0),

    -- Free cover limit: the benefit a member gets WITHOUT medical evidence. Scheme-level
    -- on purpose -- the scheme owns the limit and each member is evaluated against it,
    -- rather than every member row carrying a copy that could disagree.
    --
    -- Null means no FCL: every member is covered for their full benefit with no evidence.
    -- That is a real scheme design (small flat schemes commonly have none), and it is not
    -- the same as an FCL of zero, which would mean everyone needs underwriting.
    fcl_amount          NUMERIC(19,2) CHECK (fcl_amount IS NULL OR fcl_amount > 0),

    -- One currency for the whole scheme. A scheme quoting benefits in two currencies is
    -- not a thing, and allowing it would make the total sum insured meaningless.
    currency            CHAR(3) NOT NULL,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),

    -- The basis and its parameter must agree. A SALARY_MULTIPLE scheme with no multiple
    -- cannot value anybody, and a FLAT scheme carrying a multiple is telling two stories.
    CONSTRAINT group_scheme_basis_parameter_present CHECK (
        (benefit_basis = 'FLAT'            AND flat_benefit_amount IS NOT NULL AND salary_multiple IS NULL)
     OR (benefit_basis = 'SALARY_MULTIPLE' AND salary_multiple     IS NOT NULL AND flat_benefit_amount IS NULL)
     OR (benefit_basis = 'GRADED'          AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL)
    )
);

CREATE TABLE policy.group_scheme_grade (
    group_scheme_grade_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    policy_number         VARCHAR(20) NOT NULL REFERENCES policy.group_scheme(policy_number),
    grade_code            VARCHAR(30) NOT NULL,
    benefit_amount        NUMERIC(19,2) NOT NULL CHECK (benefit_amount > 0),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (policy_number, grade_code)
);

CREATE TABLE policy.policy_member (
    policy_member_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL REFERENCES policy.group_scheme(policy_number),

    -- The life assured. policy.life_assured_party_id stays NULL on a group policy
    -- (see V8): the lives are here, not on the master.
    member_party_id     UUID NOT NULL,

    -- Only meaningful on a GRADED scheme; resolved against group_scheme_grade.
    grade_code          VARCHAR(30),

    joined_on           DATE NOT NULL,
    left_on             DATE,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','EXITED')),

    -- Where this member stands against the scheme's FCL.
    --   WITHIN_FCL        benefit at or under the limit, no evidence needed
    --   EVIDENCE_REQUIRED benefit exceeds the limit, underwriting outstanding
    --   ACCEPTED          evidence provided and the excess granted
    --   DECLINED          evidence provided and the excess refused; cover stays at the FCL
    -- NOT a boolean: "needs evidence" and "was refused" are different states with the
    -- same covered amount, and a claim assessor has to be able to tell them apart.
    underwriting_status VARCHAR(20) NOT NULL DEFAULT 'WITHIN_FCL'
        CHECK (underwriting_status IN ('WITHIN_FCL','EVIDENCE_REQUIRED','ACCEPTED','DECLINED')),
    underwriting_case_id UUID,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),

    CONSTRAINT policy_member_exit_after_join CHECK (left_on IS NULL OR left_on >= joined_on),
    CONSTRAINT policy_member_exited_has_date CHECK (
        (status = 'ACTIVE' AND left_on IS NULL) OR (status = 'EXITED' AND left_on IS NOT NULL)
    )
);

-- One active membership per person per scheme. Partial, so somebody who leaves and
-- rejoins keeps both rows -- the history is the point, and an exited row must not block
-- a re-join.
CREATE UNIQUE INDEX ux_policy_member_active
    ON policy.policy_member (policy_number, member_party_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_policy_member_scheme ON policy.policy_member (tenant_id, policy_number, status);

-- "Every scheme this person is covered on" -- the question a claims assessor asks when a
-- death is reported and nobody knows which employer's scheme it falls under.
CREATE INDEX idx_policy_member_party ON policy.policy_member (tenant_id, member_party_id)
    WHERE status = 'ACTIVE';

CREATE TABLE policy.policy_member_benefit (
    -- Effective-dated, and this is the load-bearing decision of the whole design.
    --
    -- A claim must pay the benefit in force ON THE DATE OF EVENT. If the benefit were
    -- recomputed from today's salary, a claim on a two-year-old death would be valued at
    -- a salary the member did not have when they died. So salary and benefit are stored
    -- facts with an effective date, and a salary change writes a NEW row rather than
    -- editing the old one.
    --
    -- Confirmed with the client: a salary change does NOT silently change cover. Benefit
    -- is recalculated at renewal or by explicit endorsement, because premium was rated on
    -- the declared schedule and the FCL was tested at the old benefit -- cover that rises
    -- with payroll is unpriced risk, and it can carry a member across the FCL with nobody
    -- underwriting them.
    policy_member_benefit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_member_id    UUID NOT NULL REFERENCES policy.policy_member(policy_member_id),

    effective_from      DATE NOT NULL,

    -- Present only on a SALARY_MULTIPLE scheme; it is the input the benefit came from.
    salary_amount       NUMERIC(19,2) CHECK (salary_amount IS NULL OR salary_amount > 0),

    -- What the basis says this member is worth.
    benefit_amount      NUMERIC(19,2) NOT NULL CHECK (benefit_amount > 0),

    -- What is ACTUALLY in force, which is not always the benefit.
    --
    -- Market standard, and the client's confirmed rule: a member above the FCL is covered
    -- up to the limit immediately, and the excess is granted only on acceptance. So a
    -- member with a 150m benefit against a 100m FCL has covered_amount = 100m while
    -- evidence is outstanding, and 150m once accepted.
    --
    -- Stored rather than derived because it is the figure a claim pays, and deriving it
    -- would mean re-deciding an underwriting outcome at claim time.
    covered_amount      NUMERIC(19,2) NOT NULL CHECK (covered_amount > 0),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),

    CONSTRAINT policy_member_benefit_covered_within_benefit
        CHECK (covered_amount <= benefit_amount),
    -- One benefit per member per effective date. A second row for the same day is an
    -- ambiguity nothing could resolve: "which one was in force" would have no answer.
    UNIQUE (policy_member_id, effective_from)
);

CREATE INDEX idx_policy_member_benefit_asof
    ON policy.policy_member_benefit (policy_member_id, effective_from DESC);

ALTER TABLE policy.group_scheme ENABLE ROW LEVEL SECURITY;
CREATE POLICY group_scheme_tenant_isolation ON policy.group_scheme
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
ALTER TABLE policy.group_scheme_grade ENABLE ROW LEVEL SECURITY;
CREATE POLICY group_scheme_grade_tenant_isolation ON policy.group_scheme_grade
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
ALTER TABLE policy.policy_member ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_member_tenant_isolation ON policy.policy_member
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
ALTER TABLE policy.policy_member_benefit ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_member_benefit_tenant_isolation ON policy.policy_member_benefit
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON policy.group_scheme TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON policy.group_scheme_grade TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON policy.policy_member TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON policy.policy_member_benefit TO app_role;
