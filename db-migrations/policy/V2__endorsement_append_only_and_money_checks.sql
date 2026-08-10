-- M3 final whole-branch review fixes for the `policy` schema (Important I5 and I3).
--
-- A NEW migration rather than an edit to V1: V1 has already been applied by every
-- environment that has one, and editing an applied migration is how checksum drift and
-- "works on my machine" schemas start. scripts/migrate.sh and .github/workflows/ci-cd.yml
-- were changed in the same fix wave to apply every V*.sql per module in version order
-- (they previously hardcoded V1__create_<mod>_schema.sql), so this file is genuinely
-- applied everywhere rather than being a migration nothing runs.

-- ---------------------------------------------------------------------------------------
-- I5: policy.endorsement's stated append-only invariant was false.
-- ---------------------------------------------------------------------------------------
-- V1:63 says, inside the CREATE TABLE:  "-- Append-only: no UPDATE/DELETE grant for the
-- application role."  V1:168 then issues
--     GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policy TO app_role;
-- and `ALL TABLES` includes policy.endorsement, with no compensating REVOKE anywhere in the
-- repository. app_role -- the application's real runtime identity -- could therefore update
-- and delete rows from the policy change-history ledger.
--
-- Both sibling schemas already got this right in the same milestone:
-- policyloan/V1:147 (loan_transaction + both partitions) and audit/V1:44 (audit_log). This
-- follows their pattern.
--
-- ORDERING IS LOAD-BEARING, and is the reason this REVOKE is in V2 rather than tucked in
-- beside V1's table definition: a REVOKE issued BEFORE a later broader GRANT is silently
-- undone (policyloan/V1:137-139 records the same rule, verified empirically there). Being in
-- a later migration file guarantees it runs after V1's blanket GRANT.
--
-- Behaviour-neutral: PolicyApiImpl.applyEndorsement only ever save()s a NEW endorsement
-- (PolicyApiImpl.java:123-124); nothing in src/main updates or deletes one.
REVOKE UPDATE, DELETE ON policy.endorsement FROM app_role;

-- ALTER DEFAULT PRIVILEGES has no symmetric "default revoke" (policyloan/V1:144-146), so a
-- future table added to this schema by a later migration will again be granted full CRUD.
-- That is correct for most tables; it is wrong for an append-only ledger, and the only
-- defence is remembering to REVOKE in the same migration that creates one.
COMMENT ON TABLE policy.endorsement IS
    'Append-only policy change history. app_role holds SELECT/INSERT only -- UPDATE/DELETE revoked in V2. Any migration that re-runs a blanket GRANT over this schema must re-issue that REVOKE afterwards.';

-- ---------------------------------------------------------------------------------------
-- I3 (storage layer): no money column in this schema had a CHECK constraint.
-- ---------------------------------------------------------------------------------------
-- Task 7's Critical was a negative amount reaching PolicyAccount.increaseEncumbrance and
-- *raising* the customer's own available loan value. It was closed at the HTTP boundary
-- (policyloan.infrastructure.MoneyDto's @DecimalMin) and is now also closed in the domain
-- (PolicyApiImpl.reserveLoanValue's sign guard and PolicyAccount.increaseEncumbrance's
-- IllegalArgumentException, same fix wave). These constraints are the third layer: an
-- insurance ledger should not be able to hold a negative balance no matter which code path,
-- migration, or ad-hoc psql session writes to it.
--
-- Deliberate split between >= 0 and > 0:
--   * BALANCE columns use >= 0 -- zero is a legitimate resting state (a freshly issued
--     policy has zero cash value and zero encumbrance; PolicyApiImpl.issuePolicy:92 creates
--     PolicyAccount with BigDecimal.ZERO).
--   * MOVEMENT columns use > 0 -- a reservation of zero is meaningless, and zero is what the
--     wire-level @DecimalMin("0.01") already rejects.
-- sum_assured uses >= 0 rather than > 0 on purpose: a zero sum assured is already rejected
-- at the DTO layer, and choosing the weaker DB constraint keeps this migration from
-- retroactively invalidating any already-persisted row while still closing the actual
-- defect, which is NEGATIVE money (a negative death benefit flows to claims/finaccounting
-- in M4/M5).
ALTER TABLE policy.policy
    ADD CONSTRAINT chk_policy_sum_assured_non_negative CHECK (sum_assured_amount >= 0);
ALTER TABLE policy.policy_account
    ADD CONSTRAINT chk_policy_account_cash_value_non_negative CHECK (cash_value_amount >= 0);
ALTER TABLE policy.policy_account
    ADD CONSTRAINT chk_policy_account_encumbrance_non_negative CHECK (loan_encumbrance_amount >= 0);
ALTER TABLE policy.fund_holding
    ADD CONSTRAINT chk_fund_holding_non_negative CHECK (units >= 0 AND book_value_amount >= 0);
ALTER TABLE policy.coverage
    ADD CONSTRAINT chk_coverage_sum_assured_non_negative CHECK (sum_assured_amount >= 0);
ALTER TABLE policy.loan_value_reservation
    ADD CONSTRAINT chk_loan_value_reservation_amount_positive CHECK (amount > 0);
