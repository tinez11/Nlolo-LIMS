-- The rate a lender agreed, and the fact that this contract is paid once.
--
-- Client answer 3.1, 2026-09-22: "Rate eka adjustible maanake kuna wengine tutawapa 0.4
-- wengine 0.5" -- the rate is negotiated per lender. So it belongs on the SCHEME and not on
-- the product: two lenders writing business on the same filed credit-life product pay
-- different rates, and a product-level rate would force a duplicate product per lender, each
-- needing its own TIRA filing.

ALTER TABLE policy.group_scheme
    ADD COLUMN premium_rate_percent NUMERIC(6,4);

-- Percent PER ANNUM of the original principal. 0.5000 means 0.5%, not 50%.
--
-- Bounded because a misplaced decimal point here misprices an entire lender's book: every
-- borrower on every future file, silently, until somebody reconciles a year later. Ten
-- percent per annum is far above anything credit life charges and still leaves room for a
-- product nobody has thought of yet.
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT chk_group_scheme_premium_rate_sane
        CHECK (premium_rate_percent IS NULL
               OR (premium_rate_percent > 0 AND premium_rate_percent <= 10));

-- A loan-basis scheme MUST have one; nothing else may.
--
-- Stated in the database rather than only in issueGroupScheme because a scheme that reached
-- its first accepted file without a rate could not price a single member, and the failure
-- would surface as a whole lender's month bouncing rather than as a setup mistake somebody
-- can fix in a minute.
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT chk_group_scheme_rate_iff_loan_basis
        CHECK ((benefit_basis = 'AMORTISING_LOAN') = (premium_rate_percent IS NOT NULL));

-- A premium that is charged once, and never again.
--
-- V3 wrote this CHECK when every premium on the platform recurred. A credit-life scheme's
-- premium does not: it is charged per accepted enrolment file, against the borrowers that
-- file enrolled, and the master policy itself is never billed at all.
--
-- Note what is deliberately NOT widened: billing.billing_schedule carries the same three-value
-- CHECK and keeps it. A single-premium policy must never acquire a billing schedule, so a
-- 'SINGLE' reaching that table is a bug, and the narrow CHECK there is the second guarantee
-- behind billing.PolicyEventListener's own guard.
ALTER TABLE policy.policy
    DROP CONSTRAINT policy_premium_frequency_check;

ALTER TABLE policy.policy
    ADD CONSTRAINT policy_premium_frequency_check
        CHECK (premium_frequency IN ('MONTHLY', 'QUARTERLY', 'ANNUALLY', 'SINGLE'));
