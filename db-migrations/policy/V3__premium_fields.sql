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

ALTER TABLE policy.policy ADD CONSTRAINT chk_premium_amount_positive CHECK (premium_amount > 0);
