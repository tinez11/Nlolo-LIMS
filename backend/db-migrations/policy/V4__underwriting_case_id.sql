-- db-migrations/policy/V4__underwriting_case_id.sql
-- PolicyApi.issuePolicy(UUID underwritingCaseId, ...) has accepted this parameter since M3 and
-- silently discarded it -- no column, no entity field, not even in the PolicyIssued payload.
-- claims (M6) needs it: UnderwritingApi.checkContestability is keyed on a case id, claims holds
-- only a policy_number, and docs/01-domain-map.md:127 specifies a claims->underwriting sync
-- contestability lookup. Exposing it here keeps ONE canonical contestability implementation
-- rather than a second one re-derived from issue_date.
--
-- Deliberately NULLABLE with no backfill: there is no source of truth for the originating case
-- of an already-issued policy. Every pre-M6 policy therefore has NULL here, and claims MUST fail
-- closed on NULL (treat contestability as unverifiable and route to manual review) rather than
-- assume either answer. Opaque ref, no FK, per docs/06:29.
ALTER TABLE policy.policy ADD COLUMN underwriting_case_id UUID;

COMMENT ON COLUMN policy.policy.underwriting_case_id IS
    'Opaque ref into underwriting. NULL for policies issued before M6 -- consumers must fail closed.';
