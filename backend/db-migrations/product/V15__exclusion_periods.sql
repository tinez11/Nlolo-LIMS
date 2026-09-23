-- How long a product's exclusion windows run, in months from cover start.
--
-- Client answer 3.4, 2026-09-22: credit life carries a 12-month suicide exclusion and a
-- 12-month pre-existing-conditions exclusion, and NO general waiting period. Below the free
-- cover limit nobody is underwritten -- and with the limit at 600,000,000 against loans of
-- 10-20M, nobody is EVER underwritten -- so these two windows are the entire anti-selection
-- control this product has.
--
-- Nullable, and null on every existing row. Absent means the product has no such exclusion,
-- which is every product on this platform before credit life; their behaviour is unchanged.
-- Absent is not zero: zero would be a window that closes immediately and still exists, which
-- is a different and meaningless thing to configure.

ALTER TABLE product.product_version
    ADD COLUMN suicide_exclusion_months INTEGER,
    ADD COLUMN pre_existing_exclusion_months INTEGER;

ALTER TABLE product.product_version
    ADD CONSTRAINT chk_product_version_exclusion_windows_sane CHECK (
        (suicide_exclusion_months IS NULL OR suicide_exclusion_months > 0)
        AND (pre_existing_exclusion_months IS NULL OR pre_existing_exclusion_months > 0));
