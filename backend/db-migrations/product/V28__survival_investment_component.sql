-- db-migrations/product/V28__survival_investment_component.sql
-- IFRS 17 I3b (user decision 4): the share of each survival benefit or income instalment that is an investment
-- component -- paid in all circumstances, so neither revenue nor expense (posting guide D-01/D-05). Set per version by
-- the actuary at publish; null where it is not set, and the whole instalment is then an insurance service expense.

ALTER TABLE product.product_version
    ADD COLUMN survival_ic_percent NUMERIC(7,4) CHECK (survival_ic_percent BETWEEN 0 AND 100);
