-- Group funeral schemes (2026-10-07): an association's members and their families on one master policy.
--
-- * group_scheme gains the FUNERAL_PLAN basis: each life's cover comes from its role in the FUNERAL product's plan
--   (the plan code is on policy.funeral_policy, the table an individual funeral policy already keeps it in). No flat
--   amount, salary multiple or free cover limit -- the plan's role rules decide who may join.
-- * policy_member rows are the main members (what the bill counts); every life of a member's family, the main member
--   included, is a covered_life linked to that member -- the table that already carries role, student, benefit,
--   cover start and end, and the claim machinery for individual funeral policies.
-- * group_funeral_member: per main member, the association's number for them and the beneficiary they named. A side
--   table, so policy_member's entity (read by every scheme) does not change. A spouse taking over a family needs no
--   pending state here: the association stays policyholder, so the takeover completes when the death claim settles.

ALTER TABLE policy.group_scheme DROP CONSTRAINT group_scheme_basis_parameter_present;
ALTER TABLE policy.group_scheme DROP CONSTRAINT group_scheme_benefit_basis_check;
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT group_scheme_benefit_basis_check
        CHECK (benefit_basis IN ('FLAT','SALARY_MULTIPLE','GRADED','AMORTISING_LOAN','FUNERAL_PLAN'));
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT group_scheme_basis_parameter_present CHECK (
        (benefit_basis = 'FLAT'            AND flat_benefit_amount IS NOT NULL AND salary_multiple IS NULL)
     OR (benefit_basis = 'SALARY_MULTIPLE' AND salary_multiple     IS NOT NULL AND flat_benefit_amount IS NULL)
     OR (benefit_basis = 'GRADED'          AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL)
     OR (benefit_basis = 'AMORTISING_LOAN' AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL)
     OR (benefit_basis = 'FUNERAL_PLAN'    AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL
                                           AND fcl_amount IS NULL)
    );

-- covered_life's link to its member is V39 (separate, idempotent): a test database may apply this file before V35.

CREATE TABLE policy.group_funeral_member (
    policy_member_id          UUID PRIMARY KEY REFERENCES policy.policy_member (policy_member_id),
    tenant_id                 UUID NOT NULL,
    policy_number             VARCHAR(20) NOT NULL,
    -- The association's own number for the member (M001...). Not policy_member.member_reference: that one is
    -- insurer-issued and unique across the platform, and two associations may both number a member M001.
    association_reference     VARCHAR(30) NOT NULL,
    beneficiary_name         VARCHAR(200),
    beneficiary_relationship  VARCHAR(40),
    beneficiary_phone         VARCHAR(30),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_group_funeral_member_reference
    ON policy.group_funeral_member (tenant_id, policy_number, association_reference);

ALTER TABLE policy.group_funeral_member ENABLE ROW LEVEL SECURITY;
CREATE POLICY group_funeral_member_tenant_isolation ON policy.group_funeral_member
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON policy.group_funeral_member TO app_role;
