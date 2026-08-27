-- M13 -- replace the base rate table's free-text age band with structured bounds.
--
-- V2 (this same milestone) keyed age on `age_band VARCHAR(50)`, matching
-- product.rating_table.band on the stated grounds that the two "share one
-- age-band vocabulary". Implementing the premium calculation showed that reason
-- to be wrong on both halves:
--
-- 1. There IS no shared vocabulary to preserve. A priced version may not carry
--    AGE rating factors at all -- V2's own double-count guard forbids them, since
--    age is a key of this table -- so rating_table has no age bands on any
--    version this table applies to.
--
-- 2. Resolving an applicant's age to a band would have required PARSING the band
--    string (the reference mockup does exactly this, with /(\d+)\D+(\d+)/). That
--    makes "18-25", "18–25" (en dash) and "18 to 25" three different bands that
--    all look identical to a human authoring them, and it puts a regex between an
--    actuary's rates and the premium a customer pays. It is the same free-text
--    vocabulary void this milestone exists to close, reintroduced at the one point
--    where being wrong silently misprices a contract.
--
-- Structured bounds make the lookup a range comparison: no parsing, no dialect,
-- and an age that falls in no band is a loud 422 instead of a regex miss.
--
-- Safe as a rewrite rather than a backfill: base_rate_table was created earlier in
-- this same milestone and no environment has ever held a row in it.

ALTER TABLE product.base_rate_table DROP CONSTRAINT IF EXISTS base_rate_table_product_version_id_age_band_sex_smoker_st_key;
ALTER TABLE product.base_rate_table DROP COLUMN age_band;

ALTER TABLE product.base_rate_table
    ADD COLUMN age_from INTEGER NOT NULL,
    ADD COLUMN age_to   INTEGER NOT NULL;

-- A band must be a real interval. age_to is INCLUSIVE: "18-25" covers a 25-year-old.
ALTER TABLE product.base_rate_table
    ADD CONSTRAINT base_rate_age_range_sane CHECK (age_from >= 0 AND age_to >= age_from);

-- One band per starting age, per (sex, smoker) cell. This stops an exact duplicate;
-- it does NOT stop overlapping bands (18-25 and 20-30 both match 22), which is
-- enforced at publish in ProductApiImpl -- an overlap makes the premium depend on
-- which row the query happens to return first, which is a silent mispricing rather
-- than an error.
ALTER TABLE product.base_rate_table
    ADD CONSTRAINT base_rate_unique_cell UNIQUE (product_version_id, age_from, sex, smoker_status);

DROP INDEX IF EXISTS product.idx_base_rate_version;
CREATE INDEX idx_base_rate_version ON product.base_rate_table (product_version_id, age_from, age_to);
