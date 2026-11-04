-- db-migrations/finaccounting/V19__acted_alone.sql
-- Acting alone (2026-11-04, the user: a company with one finance person must still finish its work). A person given
-- ledger.approve-own may approve their own manual journal, period reopening or accounting-policy choice; one given
-- ifrs17.approve-own their own engine run, expense allocation or year-end close. The row says so in acted_alone, and
-- the two-person rule holds for everyone else. An engine reconciliation's acceptance is not among them.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS finaccounting.manual_journal ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS finaccounting.manual_journal DROP CONSTRAINT IF EXISTS manual_journal_check;
ALTER TABLE IF EXISTS finaccounting.manual_journal DROP CONSTRAINT IF EXISTS manual_journal_two_person;
ALTER TABLE IF EXISTS finaccounting.manual_journal
    ADD CONSTRAINT manual_journal_two_person CHECK (decided_by IS NULL OR decided_by <> preparer OR acted_alone);

-- The journal a manual journal posts carries the same mark: it is checked against its own preparer.
ALTER TABLE IF EXISTS finaccounting.journal_entry ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS finaccounting.journal_entry DROP CONSTRAINT IF EXISTS journal_manual_has_people;
ALTER TABLE IF EXISTS finaccounting.journal_entry
    ADD CONSTRAINT journal_manual_has_people CHECK (
        source_type <> 'MANUAL'
        OR (preparer IS NOT NULL AND approver IS NOT NULL AND (approver <> preparer OR acted_alone)
            AND reason IS NOT NULL));

ALTER TABLE IF EXISTS finaccounting.accounting_period ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS finaccounting.accounting_period DROP CONSTRAINT IF EXISTS accounting_period_check;
ALTER TABLE IF EXISTS finaccounting.accounting_period DROP CONSTRAINT IF EXISTS accounting_period_reopen_two_person;
ALTER TABLE IF EXISTS finaccounting.accounting_period
    ADD CONSTRAINT accounting_period_reopen_two_person
    CHECK (reopened_by IS NULL OR reopened_by <> reopen_requested_by OR acted_alone);

ALTER TABLE IF EXISTS finaccounting.accounting_policy_election ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS finaccounting.accounting_policy_election DROP CONSTRAINT IF EXISTS accounting_policy_election_check;
ALTER TABLE IF EXISTS finaccounting.accounting_policy_election DROP CONSTRAINT IF EXISTS accounting_policy_election_two_person;
ALTER TABLE IF EXISTS finaccounting.accounting_policy_election
    ADD CONSTRAINT accounting_policy_election_two_person
    CHECK (decided_by IS NULL OR decided_by <> proposed_by OR acted_alone);

ALTER TABLE IF EXISTS finaccounting.engine_run ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS finaccounting.engine_run DROP CONSTRAINT IF EXISTS engine_run_decider_not_uploader;
ALTER TABLE IF EXISTS finaccounting.engine_run
    ADD CONSTRAINT engine_run_decider_not_uploader CHECK (decided_by IS NULL OR decided_by <> uploaded_by OR acted_alone);

ALTER TABLE IF EXISTS finaccounting.expense_allocation ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS finaccounting.expense_allocation DROP CONSTRAINT IF EXISTS expense_allocation_decider_not_preparer;
ALTER TABLE IF EXISTS finaccounting.expense_allocation
    ADD CONSTRAINT expense_allocation_decider_not_preparer
    CHECK (decided_by IS NULL OR decided_by <> prepared_by OR acted_alone);

ALTER TABLE IF EXISTS finaccounting.year_end_close ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS finaccounting.year_end_close DROP CONSTRAINT IF EXISTS year_end_close_decider_not_preparer;
ALTER TABLE IF EXISTS finaccounting.year_end_close
    ADD CONSTRAINT year_end_close_decider_not_preparer
    CHECK (decided_by IS NULL OR decided_by <> prepared_by OR acted_alone);
