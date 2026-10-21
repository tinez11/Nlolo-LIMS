-- db-migrations/product/V33__term_account_rate_band.sql
-- A term account (2026-10-21): an ACCOUNT version whose rate is not declared for the product but
-- chosen per policy, at sale, from a band the version sets -- then locked for the policy's life.
--
-- Two columns on V19's table rather than a table of their own: a term account IS an account
-- version (its charges are V19's, unchanged), and the band is two more of its terms. Both null on
-- every other account version, which is what every version published before this is.
ALTER TABLE product.version_accumulation_terms
    ADD COLUMN min_rate_percent NUMERIC(7,4) CHECK (min_rate_percent BETWEEN 0 AND 100),
    ADD COLUMN max_rate_percent NUMERIC(7,4) CHECK (max_rate_percent BETWEEN 0 AND 100),
    ADD CONSTRAINT version_accumulation_terms_rate_band_shape
        CHECK ((min_rate_percent IS NULL) = (max_rate_percent IS NULL) AND max_rate_percent >= min_rate_percent);

COMMENT ON COLUMN product.version_accumulation_terms.min_rate_percent IS
    'A term account''s lowest rate (% a year) a case may name. Null on an ordinary savings account.';
COMMENT ON COLUMN product.version_accumulation_terms.max_rate_percent IS
    'A term account''s highest rate (% a year) a case may name. Null on an ordinary savings account.';
