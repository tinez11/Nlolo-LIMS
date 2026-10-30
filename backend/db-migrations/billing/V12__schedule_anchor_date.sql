-- db-migrations/billing/V12__schedule_anchor_date.sql
-- A schedule's anchor: the first due date every later one is counted from (2026-10-29). Each due date was the previous
-- one plus a month, so a policy first due on the 29th, 30th or 31st fell to the 28th after February and stayed there --
-- and fitted a thirteenth instalment into its first twelve months. Counting from the anchor, the 31st is the 31st again
-- whenever the month has one.
--
-- Backfilled from each schedule's earliest invoice: the day it was first due, before any drift. A schedule with no
-- invoice yet anchors on its next due date. Invoices already raised keep their dates -- the ledger has their cover --
-- and a drifted schedule returns to its anchor day from its next invoice on.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE billing.billing_schedule ADD COLUMN IF NOT EXISTS anchor_date DATE;

UPDATE billing.billing_schedule s
   SET anchor_date = COALESCE(
       (SELECT MIN(i.due_date) FROM billing.premium_invoice i WHERE i.billing_schedule_id = s.billing_schedule_id),
       s.next_due_date)
 WHERE s.anchor_date IS NULL;
