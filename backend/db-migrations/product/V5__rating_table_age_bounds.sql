-- Give AGE rating factors a real age range, so underwriting can actually rate age.
--
-- Until now `rating_table.band` was free text for every factor type, and AGE was rated
-- by EXACT STRING MATCH against whatever a publisher typed. UnderwritingApiImpl could
-- not produce a string to match -- it had no date of birth to band on -- so it returned
-- the sentinel "UNKNOWN", which matches nothing, and every applicant got the neutral
-- 1.0 multiplier. A 22-year-old and a 78-year-old have been underwritten identically
-- for the entire life of this platform, on the factor that dominates mortality.
--
-- Why bounds and not a parser. The AGE bands actually in this database are
-- '18-30' (99 rows), '30-39', and -- the reason this is not arguable -- the bare values
-- '21' and '18'. Two incompatible shapes already exist in production-shaped data, and a
-- bare '21' cannot be read safely: it is either the single age 21 or a band starting at
-- 21, and nothing in the record says which. Parsing would put that guess between an
-- underwriter's rules and a customer's premium. Same reasoning as
-- V3__base_rate_structured_age.sql, which removed exactly this hazard from the base
-- rate table; this is the other half of that fix.
--
-- age_to is INCLUSIVE, matching base_rate_table.
--
-- NOT VALID is deliberate, not laziness. Existing AGE rows have no bounds and cannot be
-- given any (see the bare '21'), so a validated constraint could not be added at all
-- without inventing data. Grandfathering them changes NOTHING observable: those rows
-- have never once affected a decision, because age has never been rated. New and
-- republished versions must supply bounds; old versions keep behaving exactly as they
-- do today. The constraint can be VALIDATEd later if someone chooses to correct the
-- historical rows by hand.
--
-- `band` is kept for AGE rows rather than dropped: it is what a human reads on the
-- product screen, and losing it would make an authored rating table harder to check
-- against the actuary's own paperwork. It simply stops being what the platform rates on.

ALTER TABLE product.rating_table
    ADD COLUMN age_from INTEGER,
    ADD COLUMN age_to   INTEGER;

ALTER TABLE product.rating_table
    ADD CONSTRAINT rating_table_age_bounds_shape CHECK (
        (factor_type = 'AGE' AND age_from IS NOT NULL AND age_to IS NOT NULL
             AND age_from >= 0 AND age_to >= age_from)
        OR (factor_type <> 'AGE' AND age_from IS NULL AND age_to IS NULL)
    ) NOT VALID;

-- Resolution is "find the AGE row whose range covers this applicant's age", so the
-- index leads with the version and carries the bounds.
CREATE INDEX idx_rating_table_age_range
    ON product.rating_table (product_version_id, age_from, age_to)
    WHERE factor_type = 'AGE';
