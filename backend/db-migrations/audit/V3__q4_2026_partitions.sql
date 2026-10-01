-- db-migrations/audit/V3__q4_2026_partitions.sql
-- Monthly audit_log partitions for Q4 2026 and Q1 2027, skipped where the month is already covered.
--
-- V1 created partitions through 2026-09-30. From 2026-10-01 any AFTER_COMMIT audit insert fails
-- with "no partition of relation 'audit_log' found for row" -- but ONLY where pg_partman is not
-- running. A real environment installs db-migrations/_post-migration/configure-pg-partman.sql
-- (scripts/configure-db.sh) and partman keeps months ahead on its own, under its own naming
-- (audit_log_p20261001). A Testcontainers database applies migrations and nothing else, so it has
-- no partman and no partitions past September.
--
-- Hence the exception handler rather than a plain CREATE TABLE: an unguarded one raises "would
-- overlap partition" against every partman-managed database -- which is every environment that
-- has ever been configured -- and migrate.sh would abort on dev, staging and production alike.
-- Overlap and duplicate-name are the only two failures suppressed; anything else still aborts.
DO $$
DECLARE
    month_start DATE := DATE '2026-10-01';
BEGIN
    WHILE month_start < DATE '2027-04-01' LOOP
        BEGIN
            EXECUTE format(
                'CREATE TABLE audit.%I PARTITION OF audit.audit_log FOR VALUES FROM (%L) TO (%L)',
                'audit_log_' || to_char(month_start, 'YYYY_MM'),
                month_start,
                (month_start + INTERVAL '1 month')::date);
        EXCEPTION
            WHEN invalid_object_definition OR duplicate_table THEN
                RAISE NOTICE 'audit_log already covers %, leaving it alone', month_start;
        END;
        month_start := (month_start + INTERVAL '1 month')::date;
    END LOOP;
END $$;
