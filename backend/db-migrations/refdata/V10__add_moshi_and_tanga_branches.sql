-- db-migrations/refdata/V9__add_moshi_and_tanga_branches.sql
-- Two more branches in the network: Moshi and Tanga.
--
-- A new branch is a row, not a release -- `frontend/src/api/refdata.ts` says so, and `BranchSelect`
-- reads `useReferenceCodes('BRANCH')`, so both the underwriting sale form and the agent's home branch
-- pick these up with no console change. Every module that accepts a branch validates against this set
-- (UnderwritingApiImpl, DistributionApiImpl), so the row is what makes the branch real.
--
-- V8 seeded the original five and is already applied, so it cannot be edited: Flyway checksums it.
--
-- ON CONFLICT so a re-run is a no-op rather than a failure -- `ux_reference_code_set` is
-- (code_set_key, code, jurisdiction), and migrate.sh has been re-run against an existing database
-- before now.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('BRANCH', 'MOS', 'Moshi', 'MOS', 'TZ'),
    ('BRANCH', 'TGA', 'Tanga', 'TGA', 'TZ')
ON CONFLICT (code_set_key, code, jurisdiction) DO NOTHING;
