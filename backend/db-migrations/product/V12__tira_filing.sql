-- The TIRA filing that authorises a product version to be sold.
--
-- In Tanzania a product and its rates must be filed with and approved by TIRA before sale.
-- Publishing a version recorded none of it: one ADMIN POST, no reference, no approval date, no
-- evidence the regulator had ever seen the rates -- on the single most financially consequential
-- action this platform performs.
--
-- NULLABLE with a NOT VALID check, not NOT NULL. No filing reference exists for any of the 133
-- versions already in this database, and inventing one would be worse than a null: it would be a
-- fabricated compliance record. NOT VALID enforces the rule on every insert and update from here
-- on while declining to re-litigate history -- exactly what V5, V8 and V9 each did, and each
-- recorded why. The application check in publishVersion runs first, so the failure names the
-- missing field instead of surfacing as a raw constraint violation.
--
-- VARCHAR(60) rather than a tighter guess: no TIRA reference format is recorded anywhere in this
-- repo, and this platform has twice been bitten by a column too short for a value the system
-- already produces (M7's VARCHAR(15) against a 16-character event name, M9's VARCHAR(30) against
-- a 31-character one).
--
-- WHAT THIS DOES NOT DO: it does not introduce separation of duties. One ADMIN can still change a
-- live product's price in a single call, now accompanied by a reference they typed themselves.
-- The maker-checker finding from the product audit stays OPEN.

ALTER TABLE product.product_version
    ADD COLUMN tira_filing_reference VARCHAR(60),
    ADD COLUMN tira_approval_date    DATE;

ALTER TABLE product.product_version
    ADD CONSTRAINT product_version_tira_filing_recorded
        CHECK (tira_filing_reference IS NOT NULL AND tira_approval_date IS NOT NULL)
        NOT VALID;

COMMENT ON COLUMN product.product_version.tira_filing_reference IS
    'The TIRA filing that authorises this version. NULL only on a version published before V12; '
    'nothing published after it can be null. Not verified against TIRA -- this records what a '
    'human asserts.';
