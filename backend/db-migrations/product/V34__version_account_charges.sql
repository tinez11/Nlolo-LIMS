-- db-migrations/product/V34__version_account_charges.sql
-- Account charges are kept in one place (the user, 2026-10-22): staff create them under Account charges, a product
-- version picks the ones it takes, and its policies are charged by them. A policy may still be issued on a choice of its
-- own (V32), which replaces the version's.
--
--  * A charge applies over a range of policy years, as a version's own rows did: from_policy_year, and to_policy_year
--    or none for every year after. Every charge before this applies from year 1 on.
--  * Two more "when"s, so a version's own rows convert without changing what they take:
--      PREMIUM      each premium or top-up, not a transfer in           (percent of it)
--      TRANSFER_IN  each transfer in from another scheme                 (percent of it)
--    DEPOSIT stays what it was: every deposit, transfers included.
--  * Every version's own non-zero charge rows become catalogue charges linked to it (the user's decision B). Identical
--    charges are one charge, shared. The rows stay where they are, read by nothing that a linked charge covers.

ALTER TABLE product.account_charge
    ADD COLUMN from_policy_year INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN to_policy_year   INTEGER;
ALTER TABLE product.account_charge
    ADD CONSTRAINT chk_account_charge_years
        CHECK (from_policy_year >= 1 AND (to_policy_year IS NULL OR to_policy_year >= from_policy_year));
ALTER TABLE product.account_charge DROP CONSTRAINT account_charge_charge_when_check;
ALTER TABLE product.account_charge ADD CONSTRAINT account_charge_charge_when_check
    CHECK (charge_when IN ('DEPOSIT', 'PREMIUM', 'TRANSFER_IN', 'WITHDRAWAL', 'MONTHLY', 'YEARLY', 'OPENING', 'MATURITY'));

-- The charges a version takes, in the order they were chosen. Written once, at publish, as every version table is.
CREATE TABLE product.version_account_charge (
    product_version_id UUID    NOT NULL REFERENCES product.product_version (product_version_id),
    charge_id          UUID    NOT NULL REFERENCES product.account_charge (charge_id),
    tenant_id          UUID    NOT NULL,
    position           INTEGER NOT NULL,
    PRIMARY KEY (product_version_id, charge_id)
);

ALTER TABLE product.version_account_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_account_charge_tenant_isolation ON product.version_account_charge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON product.version_account_charge TO app_role;

-- ---- The conversion -------------------------------------------------------------------------------------------------

CREATE TEMP TABLE converted_charge AS
WITH src AS (
    SELECT c.tenant_id, c.product_version_id, c.from_policy_year AS f, c.to_policy_year AS t,
           c.contribution_allocation_percent AS premium, c.transfer_allocation_percent AS transfer,
           c.monthly_policy_fee AS fee, d.default_currency AS currency
    FROM product.accumulation_charge c
    JOIN product.product_version v ON v.product_version_id = c.product_version_id
    JOIN product.product_definition d ON d.product_id = v.product_id
), parts AS (
    SELECT tenant_id, product_version_id, f, t, 'PREMIUM' AS w, 'PERCENT' AS ty, premium AS amount, currency, 1 AS k
    FROM src WHERE premium > 0
    UNION ALL
    SELECT tenant_id, product_version_id, f, t, 'TRANSFER_IN', 'PERCENT', transfer, currency, 2 FROM src WHERE transfer > 0
    UNION ALL
    SELECT tenant_id, product_version_id, f, t, 'MONTHLY', 'FLAT', fee, currency, 3 FROM src WHERE fee > 0
)
SELECT tenant_id, product_version_id, f, t, w, ty, amount, currency, k,
       CASE w
           WHEN 'PREMIUM'     THEN 'Premium allocation ' || trim_scale(amount)::text || '%'
           WHEN 'TRANSFER_IN' THEN 'Transfer-in allocation ' || trim_scale(amount)::text || '%'
           ELSE 'Monthly fee ' || currency || ' ' || trim_scale(amount)::text
       END
       || CASE
              WHEN f = 1 AND t IS NULL THEN ''
              WHEN t IS NULL           THEN ', from year ' || f
              WHEN f = t               THEN ', year ' || f
              ELSE ', years ' || f || '–' || t
          END AS name
FROM parts;

INSERT INTO product.account_charge (charge_id, tenant_id, name, description, charge_when, amount_type, amount, currency,
                                    from_policy_year, to_policy_year, active, created_by, created_at)
SELECT DISTINCT ON (tenant_id, lower(name))
       gen_random_uuid(), tenant_id, name, 'From a product''s own charges (V34)', w, ty, amount, currency, f, t, TRUE,
       'system', now()
FROM converted_charge
ON CONFLICT DO NOTHING;

INSERT INTO product.version_account_charge (product_version_id, charge_id, tenant_id, position)
SELECT cc.product_version_id, a.charge_id, cc.tenant_id,
       row_number() OVER (PARTITION BY cc.product_version_id ORDER BY cc.f, cc.k)
FROM converted_charge cc
JOIN product.account_charge a
  ON a.tenant_id = cc.tenant_id AND a.charge_when = cc.w AND a.amount_type = cc.ty AND a.amount = cc.amount
 AND a.currency = cc.currency AND a.from_policy_year = cc.f AND a.to_policy_year IS NOT DISTINCT FROM cc.t
 AND lower(a.name) = lower(cc.name);

-- Nothing a version charged may be lost: a name already taken by a different charge would leave a part unlinked.
DO $$
DECLARE
    missing INTEGER;
BEGIN
    SELECT count(*) INTO missing FROM converted_charge cc
    WHERE NOT EXISTS (SELECT 1 FROM product.version_account_charge l
                      JOIN product.account_charge a ON a.charge_id = l.charge_id
                      WHERE l.product_version_id = cc.product_version_id AND lower(a.name) = lower(cc.name));
    IF missing > 0 THEN
        RAISE EXCEPTION 'V34: % version charge(s) could not be converted -- a catalogue charge already has the name', missing;
    END IF;
END $$;

DROP TABLE converted_charge;
