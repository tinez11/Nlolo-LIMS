-- db-migrations/claims/V9__zero_annuity_settlement.sql
-- Product step 5 (D1): a death claim on a life-only annuity records a VERIFIED death that pays
-- nothing -- the right outcome, not a data-entry error. V2's "> 0" becomes ">= 0" on the three money
-- columns a claim carries. Positive stays the rule for every other claim, enforced where the product
-- is known (Claim.approve and the assessment): only an annuity's claim may be zero.
ALTER TABLE claims.claim DROP CONSTRAINT claim_approved_amount_positive;
ALTER TABLE claims.claim
    ADD CONSTRAINT claim_approved_amount_positive CHECK (approved_amount IS NULL OR approved_amount >= 0);

ALTER TABLE claims.claim_assessment DROP CONSTRAINT claim_assessment_recommended_amount_positive;
ALTER TABLE claims.claim_assessment
    ADD CONSTRAINT claim_assessment_recommended_amount_positive CHECK (recommended_amount IS NULL OR recommended_amount >= 0);

ALTER TABLE claims.settlement_decision DROP CONSTRAINT settlement_decision_approved_amount_positive;
ALTER TABLE claims.settlement_decision
    ADD CONSTRAINT settlement_decision_approved_amount_positive CHECK (approved_amount IS NULL OR approved_amount >= 0);
