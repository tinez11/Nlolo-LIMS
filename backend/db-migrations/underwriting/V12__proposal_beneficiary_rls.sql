-- db-migrations/underwriting/V12__proposal_beneficiary_rls.sql
-- The one tenant table on this platform with no row-level security.
--
-- V6 created underwriting.proposal_beneficiary with tenant_id UUID NOT NULL, an index, two
-- CHECK constraints and a COMMENT -- and never reached the ENABLE ROW LEVEL SECURITY /
-- CREATE POLICY pair that V1 writes for each of its own three tables. The GRANT, though, is
-- automatic: V1 ends with
--
--     ALTER DEFAULT PRIVILEGES IN SCHEMA underwriting
--         GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
--
-- so the table was born fully readable by the runtime role with no predicate attached to it.
-- Of the eighty tables that declare a tenant_id, this was the only one.
--
-- NOT A LEAK TODAY, and that is exactly why it survived eleven milestones. The table has one
-- query -- ProposalBeneficiaryRepository.findByTenantIdAndCaseIdOrderByCreatedAtAsc -- whose
-- own javadoc says why it filters in Java: "a read that leans on row-level security alone to
-- keep tenants apart is not the place to economise." Right, and it is also what made the
-- missing database filter invisible. The obvious next method to add is findByCaseId: shorter,
-- reads fine, and on every other table on this platform it would still be tenant-safe. Here it
-- would have returned every tenant's nominations.
--
-- WRITTEN IN V7'S FAIL-CLOSED FORM, NOT V1'S. V1's three policies were spelled
--
--     USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
--
-- and V7__rls_fail_closed.sql rewrote them, because current_setting on a GUC that was SET and
-- then RESET -- what TenantAwareDataSource does to every pooled connection borrowed without a
-- tenant -- returns the EMPTY STRING, and ''::uuid raises instead of filtering. V7 has already
-- run and will not run again, so a policy written here in V1's shape would reintroduce that bug
-- in a place nothing sweeps. NULLIF is what makes "no tenant, no rows" true rather than merely
-- intended.
--
-- No WITH CHECK clause, matching every other policy in this schema. A FOR ALL policy with USING
-- and no WITH CHECK applies its USING expression to writes as well, so an INSERT carrying
-- another tenant's id is refused by the same predicate -- and V7's sweep matches on
-- polwithcheck IS NULL, so keeping the shape identical keeps this policy inside the family any
-- future sweep will recognise.
ALTER TABLE underwriting.proposal_beneficiary ENABLE ROW LEVEL SECURITY;

CREATE POLICY proposal_beneficiary_tenant_isolation ON underwriting.proposal_beneficiary
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- No new index. The policy puts tenant_id into the predicate of every query against this table,
-- but the only query also carries case_id, and idx_proposal_beneficiary_case is selective on its
-- own -- V1's own risk_assessment and medical_disclosure carry a case index and no tenant index
-- for the same reason. A security fix is the wrong place to add speculative indexes.
