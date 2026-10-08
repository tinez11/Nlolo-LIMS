-- Yearly group funeral plans (2026-10-08): what a plan's group rate is charged per. MONTHLY bills a scheme every
-- month at members x rate, the member list moving as people join and leave. YEARLY bills it once a year at members x
-- the yearly rate, and the member list is fixed while the scheme is in force -- the policyholder (the association)
-- alone is liable for the year. Every plan written before is MONTHLY.
--
-- IF EXISTS: a test database that never applied V24 (no funeral tables) skips it, as V29 does.
ALTER TABLE IF EXISTS product.funeral_plan
    ADD COLUMN group_rate_period VARCHAR(10) NOT NULL DEFAULT 'MONTHLY'
        CONSTRAINT chk_funeral_plan_group_rate_period CHECK (group_rate_period IN ('MONTHLY','YEARLY'));
