-- The policy term: when cover starts, how long it runs, and when it matures.
--
-- policy.policy has carried issue_date and nothing else about time. That is thin
-- against the requirement, but the sharper problem is an inconsistency the platform
-- already has:
--
--   * MATURITY is a benefit_type on product.benefit_schedule and policy.coverage
--   * MATURITY is a claimType, and Claim.approve() auto-approves one straight from
--     REGISTERED with no assessment
--   * no policy anywhere records a maturity date
--
-- So the platform can pay a maturity claim but cannot say when a policy matures, and
-- nothing can sweep for policies maturing this month. These columns close that.
--
-- issue_date was also doing two jobs. A policy issued today can carry risk from next
-- month, which is why the requirement asks for a commencement date separately --
-- and why claimGates currently judges "had risk commenced" against issueDate while
-- recording in its own comments that this is an approximation.
--
-- All four columns are NULLABLE. Every policy issued before this migration has none
-- of them, and backfilling a default would invent contract terms, which on an
-- insurance policy is worse than inventing a person's occupation.
--
-- See docs/superpowers/specs/2026-09-02-build2-policy-term-design.md.

ALTER TABLE policy.policy
    ADD COLUMN commencement_date          DATE,
    ADD COLUMN policy_term_months         INTEGER,
    ADD COLUMN premium_paying_term_months INTEGER,
    ADD COLUMN maturity_date              DATE;

ALTER TABLE policy.policy
    ADD CONSTRAINT policy_term_positive
        CHECK (policy_term_months IS NULL OR policy_term_months > 0),

    ADD CONSTRAINT policy_premium_paying_term_positive
        CHECK (premium_paying_term_months IS NULL OR premium_paying_term_months > 0),

    -- A limited-payment policy pays premiums for LESS time than it covers. Paying for
    -- longer than the cover runs is not a product, it is a data error.
    ADD CONSTRAINT policy_premium_paying_term_within_term
        CHECK (premium_paying_term_months IS NULL
            OR policy_term_months IS NULL
            OR premium_paying_term_months <= policy_term_months),

    -- The derived column is pinned to its inputs in the DATABASE, not just in Java.
    --
    -- maturity_date is stored rather than computed because a maturity sweep needs an
    -- indexable WHERE clause, and because a value each caller re-derives is a value one
    -- of them eventually derives wrong. Storing it with this CHECK gives the queryable
    -- column without letting it drift from the term it came from.
    --
    -- make_interval is immutable, so it is legal in a CHECK.
    ADD CONSTRAINT policy_maturity_matches_term
        CHECK (maturity_date IS NULL
            OR (commencement_date IS NOT NULL
                AND policy_term_months IS NOT NULL
                AND maturity_date = (commencement_date + make_interval(months => policy_term_months))::date));

-- WHOLE_LIFE and ANNUITY have no maturity, and a group scheme is annually renewable
-- rather than termed, so this is deliberately partial: it indexes the policies that
-- actually mature, and stays small.
CREATE INDEX idx_policy_maturity
    ON policy.policy (tenant_id, maturity_date)
    WHERE maturity_date IS NOT NULL;
