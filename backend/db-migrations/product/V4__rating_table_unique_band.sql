-- One multiplier per (version, factor type, band).
--
-- rating_table shipped in V1 with only a NON-unique index on
-- (product_version_id, factor_type), so nothing stopped a version holding two rows
-- for the same band with different multipliers. Both readers -- ProductApiImpl's
-- strictMultiplier (premium quoting) and resolveRatingMultiplier (underwriting's
-- rules engine) -- filter to the band and then take findFirst(), so which
-- multiplier applied depended on which row Postgres returned first. A premium or an
-- underwriting loading could differ between two identical calls, with nothing
-- failing and no way to tell from the result which row was used.
--
-- The authoring form lets a user add the same band twice, so this was reachable
-- through the UI, not just by direct insert.
--
-- Same defect and same remedy as base_rate_table's `base_rate_unique_cell` in V3:
-- reject it where the rows are authored (ProductApiImpl.rejectDuplicateRatingFactors,
-- which names the offending band) and again in the schema, so a path that bypasses
-- the application still cannot create it.
--
-- Safe to add: verified there are no duplicate rows to migrate. In the steady state
-- this constraint changes nothing -- it only forecloses a state no correct version
-- was ever supposed to reach.
--
-- tenant_id is deliberately not part of the key: product_version_id is already
-- tenant-scoped by its foreign key, matching V3's base_rate_unique_cell.

ALTER TABLE product.rating_table
    ADD CONSTRAINT rating_table_unique_band UNIQUE (product_version_id, factor_type, band);
