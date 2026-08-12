-- M5: records WHICH policy reservation backed a loan, for FORENSICS ONLY.
--
-- Correction (M5 final review): this header previously claimed the DisbursementFailed compensation
-- path "needs to know which policy reservation backed a loan". It does not, and never did.
-- PolicyLoanApiImpl.markDisbursementFailed releases the encumbrance by
-- (policyNumber, principalAmount, currency) -- it never reads reservation_id, and no other code
-- path does either. The compensation works entirely without this column.
--
-- What the column IS for: originateLoan CONFIRMS the policy-side reservation before publishing
-- LoanDisbursementRequested, and until M5 the reservationId was a local variable inside that
-- method, persisted nowhere at all. So after origination there was no record linking a loan to the
-- reservation that backed it, which made a reconciliation question ("which reservation did this
-- loan consume?") unanswerable from the database. It is an audit/forensics trail, written on
-- origination and read by humans, deliberately not a functional dependency of any code path.
-- Recorded plainly here because a column whose stated purpose is a compensation path that never
-- reads it invites exactly the wrong conclusion -- either "this compensation is broken" or "this
-- column is dead code and can be dropped". Neither is true.
--
-- Nullable: loans originated before M5 have no recorded reservation, and there is no correct
-- value to invent for them.
ALTER TABLE policyloan.policy_loan ADD COLUMN reservation_id UUID;

-- DISBURSEMENT_FAILED must join policy_loan.status's allowed set -- M5 introduces the failure
-- leg of the disbursement transition (PolicyLoan.markDisbursementFailed()), and V1's inline
-- CHECK on `status` did not anticipate it. Constraint name verified against a live Postgres 16
-- container (Task 1's established method): Postgres auto-generates
-- "policy_loan_status_check" for an unnamed inline column-level CHECK, confirmed via
-- `\d policyloan.policy_loan` / pg_constraint against a throwaway container running V1 as
-- written -- exactly as written here, no correction needed.
ALTER TABLE policyloan.policy_loan DROP CONSTRAINT policy_loan_status_check;
ALTER TABLE policyloan.policy_loan ADD CONSTRAINT policy_loan_status_check CHECK (status IN
    ('RESERVED_PENDING_ORIGINATION','ORIGINATED','DISBURSEMENT_REQUESTED','DISBURSED','REPAYING',
     'SETTLED','FORCED_LAPSE_TRIGGERED','DISBURSEMENT_FAILED'));

-- No change needed to loan_transaction.transaction_type's CHECK: V1:59 already lists
-- 'REVERSAL' as an allowed value (M3's V3 comment: "A REVERSAL is modelled as its own
-- transaction_type carrying a POSITIVE amount"), so the compensating LoanTransaction this
-- milestone's failure path writes needs no schema change here.
