-- db-migrations/finaccounting/V7__q4_2026_partitions.sql
-- Monthly gl_posting partitions for Q4 2026 + Q1 2027, skipped where the month is already covered.
--
-- V1 created partitions through 2026-09-30, so from 2026-10-01 a GL posting fails with "no
-- partition of relation found for row" -- but only where pg_partman is not running. See
-- db-migrations/audit/V3 for the full reasoning behind the guard.
DO $$
DECLARE
    month_start DATE := DATE '2026-10-01';
BEGIN
    WHILE month_start < DATE '2027-04-01' LOOP
        BEGIN
            EXECUTE format(
                'CREATE TABLE finaccounting.%I PARTITION OF finaccounting.gl_posting FOR VALUES FROM (%L) TO (%L)',
                'gl_posting_' || to_char(month_start, 'YYYY_MM'),
                month_start,
                (month_start + INTERVAL '1 month')::date);
        EXCEPTION
            WHEN invalid_object_definition OR duplicate_table THEN
                RAISE NOTICE 'gl_posting already covers %, leaving it alone', month_start;
        END;
        month_start := (month_start + INTERVAL '1 month')::date;
    END LOOP;
END $$;
