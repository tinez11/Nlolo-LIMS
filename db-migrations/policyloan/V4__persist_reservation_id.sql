-- M5: the DisbursementFailed compensation path needs to know which policy reservation backed a
-- loan. originateLoan CONFIRMS the reservation before publishing LoanDisbursementRequested, so
-- by the time payment can report failure the encumbrance is already applied and must be undone
-- (docs/02-module-architecture.md:115 states this behaviour -- "on failure, releases the
-- policy-side reservation" -- but it was not implementable: reservationId was a local variable
-- in originateLoan, persisted nowhere).
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
