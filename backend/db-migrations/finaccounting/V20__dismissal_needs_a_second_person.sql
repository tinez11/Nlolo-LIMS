-- db-migrations/finaccounting/V20__dismissal_needs_a_second_person.sql
-- Dismissing an unposted event takes two people (2026-11-12, finance findings 74 and 116): dismissing removes an
-- amount from the books for good, and one person could do it with a reason and a click. Now one person asks, with the
-- reason; the event stays unposted (still blocking month-end) until a second person with ledger.approve confirms -- or
-- the same person, if they hold ledger.approve-own, recorded as acting alone. A refusal clears the request.
-- Re-runnable.
ALTER TABLE finaccounting.unposted_event ADD COLUMN IF NOT EXISTS dismissal_requested_by VARCHAR(100);
ALTER TABLE finaccounting.unposted_event ADD COLUMN IF NOT EXISTS dismissal_requested_at TIMESTAMPTZ;
ALTER TABLE finaccounting.unposted_event ADD COLUMN IF NOT EXISTS dismissal_reason VARCHAR(500);
ALTER TABLE finaccounting.unposted_event ADD COLUMN IF NOT EXISTS dismissed_alone BOOLEAN NOT NULL DEFAULT false;
