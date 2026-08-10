-- Module: refdata (Reference & Master Data)
-- M3 addition: seeds the refdata code sets policy's lifecycle state machine consults --
-- policy.application.PolicyApiImpl.suspendPolicy resolves the SUSPENDED-eligible product
-- categories from POLICY_SUSPENSION_ELIGIBLE_CATEGORIES rather than a hardcoded category
-- list (Deliverable 3 Rev 2 §3's flagged, previously-unresolved item). This was originally
-- anticipated to land alongside Task 6's policyloan work (see the code_set_key's own
-- forward-reference comment in db-migrations/policy/V1__create_policy_schema.sql), but
-- Task 2's own suspend/resume lifecycle test needs at least one eligible category seeded to
-- be genuinely falsifiable (i.e. to prove an eligible category can actually succeed, not
-- only that an ineligible one is rejected) -- so it is seeded here instead. A later task
-- (originally-planned Task 6) can extend this same code set with further categories without
-- colliding with this file, since INSERT here is additive, not exhaustive.
--
-- PLACEHOLDER values, same caveat as V1__create_refdata_schema.sql's seed data: not
-- production-ready, pending Actuarial/Product sign-off on which categories genuinely support
-- a SUSPENDED status (e.g. group schemes with SACCO-linked premium collection).
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('POLICY_SUSPENSION_ELIGIBLE_CATEGORIES', 'GROUP_LIFE', 'Group Life', 'true', 'TZ');   -- PLACEHOLDER, pending B1 follow-up

-- Task 6 (policyloan) addition: the loan interest rate policyloan.application.PolicyLoanApiImpl
-- resolves via ReferenceDataApi.getValue at loan origination, recorded per-loan (effective-dated)
-- in policyloan.loan_interest_term rather than hardcoded (Deliverable 3 Rev 2, L1). Appended to
-- this existing V2 file rather than a new migration -- V2 is already taken by this same file's
-- POLICY_SUSPENSION_ELIGIBLE_CATEGORIES seed above; INSERT here is additive, not exhaustive, same
-- as that earlier addition's own comment already explains.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE', 'DEFAULT', 'Policy loan annual interest rate (percent)', '12.0', 'TZ'); -- PLACEHOLDER, pending Actuarial sign-off
