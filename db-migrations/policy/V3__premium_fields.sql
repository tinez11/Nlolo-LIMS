-- Deliverable 8 / M4 (billing): policy never stored a premium amount, frequency, or currency
-- anywhere -- premiumFrequency was accepted on PolicyApi.IssueRequest since M3 but silently
-- dropped by PolicyApiImpl.issuePolicy, and no premium_amount column existed at all. billing's
-- schedule generation needs both; per docs/01-domain-map.md, policy establishes what is owed
-- (the premium), billing only schedules against it -- this is the additive fix, mirroring
-- ProductSnapshotView's M3 addition of `category`/`surrenderChargeScheduleJson`.
ALTER TABLE policy.policy
    ADD COLUMN premium_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN premium_currency CHAR(3) NOT NULL DEFAULT 'TZS',
    ADD COLUMN premium_frequency VARCHAR(10) NOT NULL DEFAULT 'MONTHLY'
        CHECK (premium_frequency IN ('MONTHLY', 'QUARTERLY', 'ANNUALLY'));

-- The DEFAULTs above exist ONLY to satisfy the NOT NULL constraint against any pre-existing
-- row (there are none in a fresh environment, but ALTER TABLE ADD COLUMN NOT NULL without a
-- default fails outright against a populated table) -- every future INSERT (i.e. every
-- issuePolicy call from this commit forward) supplies real values explicitly; the defaults
-- are never relied upon by application code.
ALTER TABLE policy.policy ALTER COLUMN premium_amount DROP DEFAULT;
ALTER TABLE policy.policy ALTER COLUMN premium_frequency DROP DEFAULT;

-- Task 1 review, I1 (Important): DROP DEFAULT above only stops the default from applying to
-- FUTURE inserts -- it does not retroactively change the `0` this migration just backfilled
-- onto every PRE-EXISTING row (policy.policy has been live since M3; a real deployment can
-- already have issued policies by the time this migration runs). A plain
-- `ADD CONSTRAINT ... CHECK (...)` validates every existing row at ALTER time and Postgres
-- refuses to add a constraint any row violates -- so against a populated table this migration
-- would fail outright with a real backfilled 0 that has no real premium amount to retroactively
-- compute. NOT VALID is the correct fix: it skips validating existing rows at ALTER time (so
-- the migration succeeds regardless of what is already in the table) while still enforcing the
-- check on every INSERT/UPDATE from this point forward, which is exactly what is needed here --
-- there is no real historical premium to backfill onto pre-M4 policies, only a going-forward
-- guarantee. Empirically verified against a real Postgres 16 container with a pre-existing
-- premium_amount=0 row: the migration succeeds where a validating CHECK would have failed, and
-- a new INSERT/UPDATE of 0 is still rejected -- see task-1-report.md, "Fix round 1".
ALTER TABLE policy.policy ADD CONSTRAINT chk_premium_amount_positive CHECK (premium_amount > 0) NOT VALID;
