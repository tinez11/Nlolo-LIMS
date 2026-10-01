-- db-migrations/audit/V3__q4_2026_partitions.sql
-- Add monthly audit_log partitions for Q4 2026 and Q1 2027.
--
-- V1 created partitions through 2026-09-30. Running on or after 2026-10-01,
-- any AFTER_COMMIT audit insert fails with "no partition of relation
-- 'audit_log' found for row". These partitions extend coverage through
-- 2027-03-31, matching the operational cadence in V1.
CREATE TABLE audit.audit_log_2026_10 PARTITION OF audit.audit_log
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE audit.audit_log_2026_11 PARTITION OF audit.audit_log
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE audit.audit_log_2026_12 PARTITION OF audit.audit_log
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE audit.audit_log_2027_01 PARTITION OF audit.audit_log
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE audit.audit_log_2027_02 PARTITION OF audit.audit_log
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE audit.audit_log_2027_03 PARTITION OF audit.audit_log
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
