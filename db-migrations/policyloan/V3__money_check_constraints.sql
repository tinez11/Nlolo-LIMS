-- M3 final whole-branch review, Important I3 (storage layer), `policyloan` half.
-- Companion to db-migrations/policy/V2 -- see that file's header for the >= 0 vs > 0
-- rationale and for why this is a new migration rather than an edit to V1.
--
-- Task 7 rated the negative-amount hole a Critical and closed it at the HTTP boundary with
-- policyloan.infrastructure.MoneyDto's @DecimalMin("0.01"). Nothing below that boundary
-- enforced it: `grep -n CHECK` over both schemas' V1 returned only status/enum and
-- share_percent checks, so principal_amount, loan_transaction.amount and
-- scheduled_amount all accepted negatives at the storage layer.

-- Movement/quantity columns: a zero or negative loan principal, ledger entry, or scheduled
-- installment is meaningless in every direction this module writes them. A REVERSAL is
-- modelled as its own transaction_type carrying a POSITIVE amount (see the CHECK on
-- transaction_type in V1), not as a negative REPAYMENT -- if M5 ever changes that, this
-- constraint is the thing that will stop it silently, which is the intent.
ALTER TABLE policyloan.policy_loan
    ADD CONSTRAINT chk_policy_loan_principal_positive CHECK (principal_amount > 0);
ALTER TABLE policyloan.repayment_schedule
    ADD CONSTRAINT chk_repayment_schedule_amount_positive CHECK (scheduled_amount > 0);

-- loan_transaction is PARTITIONED BY RANGE. Unlike row-level security, policies and ACLs
-- (which Postgres does NOT cascade -- see V1's long comment and V2's event trigger), a CHECK
-- constraint added to a partitioned parent IS recursively applied to every existing partition
-- and automatically inherited by every future one, so this single statement covers
-- loan_transaction_2026_08, loan_transaction_2026_09, and everything pg_partman creates later.
ALTER TABLE policyloan.loan_transaction
    ADD CONSTRAINT chk_loan_transaction_amount_positive CHECK (amount > 0);

-- An interest rate of exactly 0 is legitimate (an interest-free staff or promotional loan);
-- a negative one is not.
ALTER TABLE policyloan.loan_interest_term
    ADD CONSTRAINT chk_loan_interest_term_rate_non_negative CHECK (rate >= 0);
