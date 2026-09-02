-- Module: policyloan (Policy Loans & Cash Value)
-- Closes the gap that loan interest was NEVER ACCRUED. Before this migration the module
-- resolved TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE at origination, persisted it (effective-dated)
-- into policyloan.loan_interest_term, exposed it as LoanView.currentInterestRate, and folded
-- INTEREST_ACCRUAL entries into the outstanding balance
-- (PolicyLoanApiImpl.computeOutstandingBalance) -- but NOTHING anywhere wrote such an entry.
-- V1:59's transaction_type CHECK already permitted 'INTEREST_ACCRUAL'; there was simply no
-- producer. An outstanding loan balance therefore stayed flat forever and the rate was
-- decorative: docs/01-domain-map.md:44 lists "interest accrual" as this module's
-- responsibility, and :224 defines Forced Lapse as firing when "loan balance plus interest
-- exceeds cash value" -- neither was reachable.
--
-- The accrual itself is a cross-tenant SQL sweep in
-- db-migrations/_post-migration/configure-loan-interest-accrual.sql, for the reason
-- Application.java:7-9, PolicyApiImpl.java:~300 and configure-commission-close.sql all
-- already state: a Java @Scheduled thread has no TenantContext, so every RLS-protected table
-- shows it zero rows, and app_role must never bypass RLS. This migration adds only the column
-- that sweep needs to hand the CROSS-MODULE half of the work back to per-tenant Java.

-- Set by the accrual sweep whenever it writes an INTEREST_ACCRUAL entry; cleared by
-- policyloan.application.PolicyLoanApiImpl.evaluateForcedLapse once a tenant-scoped caller has
-- compared the grown balance against the policy's cash value.
--
-- This column exists because of a MODULE BOUNDARY, not for convenience. Forced lapse is
-- "balance + interest > cash value", and cash value lives in policy.policy_account -- which
-- docs/01-domain-map.md:46 says this module reads "via its public API, never its tables
-- directly". The SQL sweep therefore cannot evaluate the condition itself without reaching
-- across a schema the module is forbidden to read, and Java cannot sweep cross-tenant at all.
-- So the sweep records only what it legitimately knows -- "this loan's balance grew, the
-- shortfall condition must be re-checked" -- and the tenant-scoped Java path does the compare
-- through PolicyApi. This is the same honest split configure-commission-close.sql documents:
-- guaranteed-on-time STATE in SQL, anything needing per-tenant Java in the application.
ALTER TABLE policyloan.policy_loan ADD COLUMN forced_lapse_review_due_at TIMESTAMPTZ;

-- Partial index: the review queue is read by "which loans are pending a shortfall re-check",
-- which is a small minority of rows at any moment (only those accrued since the last check).
-- A partial index keeps it proportional to the queue, not to the loan book.
CREATE INDEX idx_policy_loan_forced_lapse_review ON policyloan.policy_loan (tenant_id, forced_lapse_review_due_at)
    WHERE forced_lapse_review_due_at IS NOT NULL;

-- No GRANT needed: V1's `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA
-- policyloan TO app_role` already covers policy_loan, and column-level privileges are not in
-- play (the grant is table-wide). No RLS change either -- policy_loan's
-- policy_loan_tenant_isolation policy from V1 filters rows, and adding a column to an
-- already-RLS-enabled table does not alter the policy.
