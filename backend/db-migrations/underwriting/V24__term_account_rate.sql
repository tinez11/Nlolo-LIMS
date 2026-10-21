-- db-migrations/underwriting/V24__term_account_rate.sql
-- A term account's rate (2026-10-21): the case names it from the product version's band, the way it names its sum
-- assured and term, and the policy is issued at it and keeps it for life. Null on every other case -- a rate is
-- refused on a product that is not a term account, so null means "this product takes none", never "not yet asked".
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN proposed_rate_percent NUMERIC(7,4) CHECK (proposed_rate_percent BETWEEN 0 AND 100);

COMMENT ON COLUMN underwriting.underwriting_case.proposed_rate_percent IS
    'A term account''s rate, % a year, inside its version''s band (product V33). Locked onto the account at issuance.';
