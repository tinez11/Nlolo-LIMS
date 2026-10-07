-- Group funeral schemes (2026-10-07): a group proposal on a FUNERAL product. Its basis is FUNERAL_PLAN -- each life's
-- cover comes from its role in the chosen plan -- and it carries no typed premium (members x the plan's group rate,
-- computed at issue). Its opening schedule is families: one main member per association reference, with the spouse,
-- children and others declared beside them, each a name on the schedule rather than a registered party.

DO $$
DECLARE c TEXT;
BEGIN
    -- V9 declared the basis check inline, so its name is generated: find it rather than guess.
    SELECT con.conname INTO c
      FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_namespace ns ON ns.oid = rel.relnamespace
     WHERE ns.nspname = 'underwriting' AND rel.relname = 'proposal_group_scheme' AND con.contype = 'c'
       AND pg_get_constraintdef(con.oid) LIKE '%benefit_basis%ANY%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE underwriting.proposal_group_scheme DROP CONSTRAINT %I', c);
    END IF;
END $$;

ALTER TABLE underwriting.proposal_group_scheme
    ADD CONSTRAINT proposal_group_scheme_basis_known
        CHECK (benefit_basis IN ('FLAT','SALARY_MULTIPLE','GRADED','FUNERAL_PLAN')),
    ADD COLUMN plan_code VARCHAR(20),
    ALTER COLUMN premium_amount DROP NOT NULL;

ALTER TABLE underwriting.proposal_group_scheme DROP CONSTRAINT proposal_group_basis_parameter_present;
ALTER TABLE underwriting.proposal_group_scheme
    ADD CONSTRAINT proposal_group_basis_parameter_present CHECK (
        (benefit_basis = 'FLAT'            AND flat_benefit_amount IS NOT NULL AND salary_multiple IS NULL AND plan_code IS NULL)
     OR (benefit_basis = 'SALARY_MULTIPLE' AND salary_multiple     IS NOT NULL AND flat_benefit_amount IS NULL AND plan_code IS NULL)
     OR (benefit_basis = 'GRADED'          AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL AND plan_code IS NULL)
     OR (benefit_basis = 'FUNERAL_PLAN'    AND plan_code IS NOT NULL AND flat_benefit_amount IS NULL
                                           AND salary_multiple IS NULL AND fcl_amount IS NULL)
    ),
    -- A typed premium on every basis but the funeral plan's, which is computed at issue.
    ADD CONSTRAINT proposal_group_premium_iff_not_funeral_plan
        CHECK ((benefit_basis = 'FUNERAL_PLAN') = (premium_amount IS NULL));

CREATE TABLE underwriting.proposal_group_life (
    proposal_group_life_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL,
    case_id                  UUID NOT NULL REFERENCES underwriting.proposal_group_scheme(case_id),
    position                 INTEGER NOT NULL,
    -- The association's own number for the member; the family's lives share it.
    member_reference         VARCHAR(40) NOT NULL,
    role                     VARCHAR(12) NOT NULL CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    full_name                VARCHAR(200) NOT NULL,
    date_of_birth            DATE NOT NULL,
    sex                      VARCHAR(6),
    id_number                VARCHAR(40),
    student                  BOOLEAN NOT NULL DEFAULT false,
    beneficiary_name         VARCHAR(200),
    beneficiary_relationship VARCHAR(40),
    beneficiary_phone        VARCHAR(30),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (case_id, position)
);
-- One main member per reference: two would be two families under one number.
CREATE UNIQUE INDEX ux_proposal_group_life_main ON underwriting.proposal_group_life (case_id, member_reference)
    WHERE role = 'MAIN_MEMBER';
CREATE INDEX idx_proposal_group_life_case ON underwriting.proposal_group_life (case_id, position);

ALTER TABLE underwriting.proposal_group_life ENABLE ROW LEVEL SECURITY;
CREATE POLICY proposal_group_life_tenant_isolation ON underwriting.proposal_group_life
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, DELETE ON underwriting.proposal_group_life TO app_role;
