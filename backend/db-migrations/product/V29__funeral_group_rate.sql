-- Group funeral schemes (2026-10-07). How a FUNERAL version may be sold -- to individual families, to group schemes
-- (an association or employer's members under one master policy), or both -- and each plan's rate per member per
-- month for a scheme, the member's whole family included. Every version written before is INDIVIDUAL.
--
-- And a dependant's yearly premium may now be 0: "included in the main member's premium", a flat family rate. The
-- main member's premium stays above zero (checked at publish), so no policy is ever free.
--
-- IF EXISTS throughout: in a real database V24 always ran first; in a test database that never applied V24 (no
-- funeral tables) this is a no-op rather than a failure.

ALTER TABLE IF EXISTS product.funeral_terms
    ADD COLUMN sold_as VARCHAR(10) NOT NULL DEFAULT 'INDIVIDUAL'
        CONSTRAINT chk_funeral_terms_sold_as CHECK (sold_as IN ('INDIVIDUAL','GROUP','BOTH'));

ALTER TABLE IF EXISTS product.funeral_plan
    ADD COLUMN group_monthly_rate NUMERIC(19,2)
        CONSTRAINT chk_funeral_plan_group_rate_positive CHECK (group_monthly_rate IS NULL OR group_monthly_rate > 0);

DO $$
DECLARE c TEXT;
BEGIN
    -- V24 declared the premium check inline, so its name is generated: find it rather than guess.
    SELECT con.conname INTO c
      FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_namespace ns ON ns.oid = rel.relnamespace
     WHERE ns.nspname = 'product' AND rel.relname = 'funeral_premium' AND con.contype = 'c'
       AND pg_get_constraintdef(con.oid) LIKE '%yearly_premium > %';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE product.funeral_premium DROP CONSTRAINT %I', c);
    END IF;
END $$;
ALTER TABLE IF EXISTS product.funeral_premium
    ADD CONSTRAINT chk_funeral_premium_not_negative CHECK (yearly_premium >= 0);
