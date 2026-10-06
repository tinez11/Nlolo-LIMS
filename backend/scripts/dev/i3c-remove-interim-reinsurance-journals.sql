-- scripts/dev/i3c-remove-interim-reinsurance-journals.sql
-- ONE-OFF DEVELOPMENT CLEANUP for IFRS 17 I3c (reinsurance on the monthly bordereau), user answer Q7 2026-10-06.
-- Not a migration and never run by tests: only the development ledger holds journals posted by the interim rules
-- that I3c replaces --
--   * reinsurance.CessionRecorded (K-01 interim): posted the ceded SUM ASSURED as if it were reinsurance premium owed,
--     Dr 1436 / Cr 1430 -- about 815 journals, ~TZS 1.2bn, none of them a real premium;
--   * reinsurance.RecoveryConfirmed (B-05 interim): credited the claims expense 5110 instead of 6120.
-- From I3c the bordereau job posts the ceded premium month by month and a recovery posts to 6120 at claim approval,
-- so these rows would double-count and misstate. Run once, after reinsurance V5, as the database owner:
--
--   docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -1 \
--       < backend/scripts/dev/i3c-remove-interim-reinsurance-journals.sql
--
-- Posted journals are immutable by trigger (finaccounting V10 7a) for every role, the owner included. This script
-- is the one sanctioned exception, for the development ledger only: it disables user triggers for its own session
-- (session_replication_role = replica, superuser only) and nothing else. Idempotent: a second run deletes nothing.

SET LOCAL session_replication_role = replica;

-- What is about to go, for the run's record.
SELECT source_event, count(*) AS journals,
       (SELECT coalesce(sum(p.amount), 0) FROM finaccounting.gl_posting p
         JOIN finaccounting.journal_entry j2 ON j2.journal_entry_id = p.journal_entry_id
        WHERE j2.source_event = j.source_event AND p.direction = 'DR') AS debits
  FROM finaccounting.journal_entry j
 WHERE source_event IN ('reinsurance.CessionRecorded', 'reinsurance.RecoveryConfirmed')
 GROUP BY source_event;

-- A reversal pointing at one of them would dangle; there should be none (I4's manual journals are not merged).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM finaccounting.journal_entry r
                 JOIN finaccounting.journal_entry j ON j.journal_entry_id = r.reverses_journal_id
                WHERE j.source_event IN ('reinsurance.CessionRecorded', 'reinsurance.RecoveryConfirmed')) THEN
        RAISE EXCEPTION 'A journal reverses an interim reinsurance journal -- resolve it by hand before this cleanup';
    END IF;
END $$;

DELETE FROM finaccounting.gl_posting p
 USING finaccounting.journal_entry j
 WHERE j.journal_entry_id = p.journal_entry_id
   AND j.source_event IN ('reinsurance.CessionRecorded', 'reinsurance.RecoveryConfirmed');

DELETE FROM finaccounting.journal_entry
 WHERE source_event IN ('reinsurance.CessionRecorded', 'reinsurance.RecoveryConfirmed');

-- Anything the engine queued for those two events (an unclassified policy's cession, say) has no rule any more.
DELETE FROM finaccounting.unposted_event
 WHERE event_type IN ('reinsurance.CessionRecorded', 'reinsurance.RecoveryConfirmed');

SELECT count(*) AS interim_reinsurance_journals_left FROM finaccounting.journal_entry
 WHERE source_event IN ('reinsurance.CessionRecorded', 'reinsurance.RecoveryConfirmed');
