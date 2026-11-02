-- db-migrations/claims/V13__credit_life_statement_evidence.sql
-- A credit-life claim pays what the lender says the borrower still owed (2026-10-30), so the lender's own loan
-- statement must be on the claim before it is approved (2026-11-02). Evidence gains a KIND, null for every ordinary
-- document; LOAN_STATEMENT marks the lender's statement.
ALTER TABLE claims.claim_evidence ADD COLUMN kind VARCHAR(30);
ALTER TABLE claims.claim_evidence
    ADD CONSTRAINT claim_evidence_kind_known CHECK (kind IS NULL OR kind IN ('LOAN_STATEMENT'));

-- Why a manager approved more than the repayment schedule says was still owed: the lender's statement is the debt,
-- the schedule only the insurer's estimate, so paying above it is allowed -- with a reason, kept on the decision.
ALTER TABLE claims.settlement_decision ADD COLUMN approval_note TEXT;
