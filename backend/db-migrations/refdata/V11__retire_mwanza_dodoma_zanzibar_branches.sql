-- db-migrations/refdata/V11__retire_mwanza_dodoma_zanzibar_branches.sql
-- The branch network is Dar es Salaam, Moshi, Tanga and Arusha. Mwanza, Dodoma and Zanzibar leave it.
--
-- MOVED OUT OF THE SET, NOT DELETED, AND NOT FLAGGED. Three options were on the table and the reasons
-- for this one are worth keeping, because the obvious two are both worse here:
--
--   * DELETE loses the only record of what a code meant. At the time of writing dev holds 15
--     finaccounting.policy_classification rows on ZNZ and 1 on DOD. A classification row is immutable
--     by trigger (CLASSIFICATION_IMMUTABLE) and is a posting dimension on journals already in the
--     ledger, so those codes outlive the branch permanently. Deleting the row would leave a code in
--     the ledger that the platform could no longer explain.
--
--   * A `retired_at` COLUMN is the textbook answer and costs far too much here: 88 test classes
--     hand-list the refdata migrations they apply, 82 of them including V8. A new column that the
--     entity maps but those hand-built schemas lack breaks every one of them, so retiring three rows
--     would have meant an 88-file edit. The schema is not the right place to carry this.
--
-- Moving the key does the whole job, because `ReferenceDataApiImpl.getCodes` is BOTH the pickers'
-- options and the validators' allow-list -- `BranchSelect` reads it over GET /reference-codes/BRANCH,
-- and UnderwritingApiImpl and DistributionApiImpl check against it before accepting a branch. Out of
-- the set means out of both: a retired branch disappears from every dropdown AND stops being accepted
-- on a new sale or an agent placement, which is what retiring a branch means. Everything already
-- recorded against it is untouched.
--
-- BRANCH_RETIRED is deliberately NOT added to ReferenceDataController's per-realm allowlist. It is an
-- archive for someone asking what ZNZ was, not a list anybody should be served while choosing.
--
-- Consequence worth knowing: a console showing a historical record on a retired branch has no label
-- for it and prints the raw code (`ZNZ`, not `Zanzibar`). That is exactly the fallback BranchSelect's
-- `showValueAlone` branch already exists for.
--
-- HEAD_OFFICE_BRANCH/DEFAULT still resolves to DSM, which stays live, so sales that name no branch are
-- unaffected.
--
-- Naturally idempotent: a second run matches nothing.
UPDATE refdata.reference_code_set
   SET code_set_key = 'BRANCH_RETIRED',
       updated_at   = now()
 WHERE code_set_key = 'BRANCH'
   AND code IN ('MWZ', 'DOD', 'ZNZ');
