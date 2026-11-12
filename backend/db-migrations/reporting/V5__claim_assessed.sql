-- db-migrations/reporting/V5__claim_assessed.sql
-- The day a claim was first assessed (2026-11-12, analytics plan 5): claims.ClaimAssessed, which reporting used to
-- ignore. The first assessment only -- a second one does not move it -- so "days to assess" means time to the first
-- look. A rebuild replays the audit journal, so claims assessed before this column existed fill in too.
ALTER TABLE reporting.report_claim ADD COLUMN IF NOT EXISTS assessed_on DATE;
