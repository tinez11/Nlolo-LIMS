-- db-migrations/payment/V7__q4_2026_partitions.sql
-- Monthly partitions for payment_transaction AND disbursement_instruction, Q4 2026 + Q1 2027,
-- skipped where the month is already covered.
--
-- V1 created partitions through 2026-09-30, so from 2026-10-01 a collection or a disbursement
-- fails with "no partition of relation found for row" -- but only where pg_partman is not running.
-- See db-migrations/audit/V3 for the full reasoning behind the guard; in short, a configured
-- environment already has these months from partman and a plain CREATE TABLE would abort
-- migrate.sh there.
DO $$
DECLARE
    parent TEXT;
    month_start DATE;
BEGIN
    FOREACH parent IN ARRAY ARRAY['payment_transaction', 'disbursement_instruction'] LOOP
        month_start := DATE '2026-10-01';
        WHILE month_start < DATE '2027-04-01' LOOP
            BEGIN
                EXECUTE format(
                    'CREATE TABLE payment.%I PARTITION OF payment.%I FOR VALUES FROM (%L) TO (%L)',
                    parent || '_' || to_char(month_start, 'YYYY_MM'),
                    parent,
                    month_start,
                    (month_start + INTERVAL '1 month')::date);
            EXCEPTION
                WHEN invalid_object_definition OR duplicate_table THEN
                    RAISE NOTICE '% already covers %, leaving it alone', parent, month_start;
            END;
            month_start := (month_start + INTERVAL '1 month')::date;
        END LOOP;
    END LOOP;
END $$;
