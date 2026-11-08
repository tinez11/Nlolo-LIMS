-- db-migrations/billing/V18__waiver_count.sql
-- A waiver can be undone (2026-11-08), and the invoice waived again: each waiver posts to the ledger under its own
-- reference, so the invoice counts its waivers. Every invoice waived before today was waived once.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE billing.premium_invoice ADD COLUMN IF NOT EXISTS waiver_count INT NOT NULL DEFAULT 0;

UPDATE billing.premium_invoice SET waiver_count = 1 WHERE status = 'WAIVED' AND waiver_count = 0;
