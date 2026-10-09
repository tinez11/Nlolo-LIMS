-- scripts/dev/remove-investment-contract-cessions.sql
-- ONE-OFF DEVELOPMENT CLEANUP for reinsurance V8 (2026-10-09, the user's fix: investment contracts are never ceded).
-- Not a migration and never run by tests. Until V8 every policy but a group scheme was ceded on its sum assured, so the
-- development database holds cessions on the four portfolios accounting measures as IFRS 9 investment contracts (SAV,
-- DEP, PEN, DANN) -- 72 on 2026-10-09, half of each deposit or savings balance ceded as cover and as premium -- and one
-- recovery posted on a deposit's death claim (POL-CD17759E, TZS 3,000,000, Dr 1420 / Cr 6120).
--
-- Run once, after reinsurance V8, as the database owner:
--
--   docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -1 \
--       < backend/scripts/dev/remove-investment-contract-cessions.sql
--
-- Removes, as i3c-remove-interim-reinsurance-journals.sql did (the clean-dev-ledger rule): those cessions, their cover
-- periods, any recovery on those policies and that recovery's journal. Posted journals are immutable by trigger
-- (finaccounting V10 7a); session_replication_role = replica is the sanctioned development-only exception. No bordereau
-- had charged any of these policies (September charged TERM_LIFE only), which the script checks rather than assumes.
-- Idempotent: a second run removes nothing.

SET LOCAL session_replication_role = replica;

-- Every projection learns its portfolio, so the recovery guard knows these policies from now on.
UPDATE reinsurance.policy_projection pp
   SET portfolio_code = p.portfolio_code
  FROM policy.policy p
 WHERE p.policy_number = pp.policy_number
   AND pp.portfolio_code IS DISTINCT FROM p.portfolio_code;

CREATE TEMP TABLE investment_policy ON COMMIT DROP AS
SELECT tenant_id, policy_number FROM reinsurance.policy_projection
 WHERE portfolio_code IN ('SAV', 'DEP', 'PEN', 'DANN');

CREATE TEMP TABLE wrong_recovery ON COMMIT DROP AS
SELECT r.recovery_id FROM reinsurance.claim_recovery r
  JOIN claims.claim c ON c.claim_id = r.claim_id
  JOIN investment_policy i ON i.tenant_id = r.tenant_id AND i.policy_number = c.policy_number;

-- What is about to go, for the run's record.
SELECT (SELECT count(*) FROM reinsurance.cession c JOIN investment_policy i USING (tenant_id, policy_number)) AS cessions,
       (SELECT coalesce(sum(c.ceded_premium_amount), 0) FROM reinsurance.cession c
          JOIN investment_policy i USING (tenant_id, policy_number)) AS ceded_premium,
       (SELECT count(*) FROM wrong_recovery) AS recoveries,
       (SELECT coalesce(sum(p.amount), 0) FROM finaccounting.gl_posting p
         WHERE p.source_event = 'reinsurance.RecoveryCalculated' AND p.direction = 'DR'
           AND p.source_ref IN (SELECT recovery_id::text FROM wrong_recovery)) AS recovery_posted;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM reinsurance.bordereau_line b JOIN investment_policy i USING (tenant_id, policy_number)) THEN
        RAISE EXCEPTION 'A bordereau already charged an investment contract -- resolve it by hand before this cleanup';
    END IF;
    IF EXISTS (SELECT 1 FROM reinsurance.statement_item s JOIN wrong_recovery w ON w.recovery_id = s.item_id
                WHERE s.item_type = 'RECOVERY') THEN
        RAISE EXCEPTION 'A statement carries a recovery on an investment contract -- resolve it by hand first';
    END IF;
    IF EXISTS (SELECT 1 FROM finaccounting.journal_entry r
                 JOIN finaccounting.journal_entry j ON j.journal_entry_id = r.reverses_journal_id
                WHERE j.source_event = 'reinsurance.RecoveryCalculated'
                  AND j.source_ref IN (SELECT recovery_id::text FROM wrong_recovery)) THEN
        RAISE EXCEPTION 'A journal reverses a recovery on an investment contract -- resolve it by hand first';
    END IF;
END $$;

DELETE FROM finaccounting.gl_posting p
 USING finaccounting.journal_entry j
 WHERE j.journal_entry_id = p.journal_entry_id
   AND j.source_event = 'reinsurance.RecoveryCalculated'
   AND j.source_ref IN (SELECT recovery_id::text FROM wrong_recovery);

DELETE FROM finaccounting.journal_entry
 WHERE source_event = 'reinsurance.RecoveryCalculated'
   AND source_ref IN (SELECT recovery_id::text FROM wrong_recovery);

DELETE FROM finaccounting.unposted_event
 WHERE event_type IN ('reinsurance.RecoveryCalculated', 'reinsurance.CessionRecorded')
   AND policy_number IN (SELECT policy_number FROM investment_policy);

DELETE FROM reinsurance.claim_recovery WHERE recovery_id IN (SELECT recovery_id FROM wrong_recovery);

DELETE FROM reinsurance.cover_period c USING investment_policy i
 WHERE c.tenant_id = i.tenant_id AND c.policy_number = i.policy_number;

DELETE FROM reinsurance.cession c USING investment_policy i
 WHERE c.tenant_id = i.tenant_id AND c.policy_number = i.policy_number;

SELECT (SELECT count(*) FROM reinsurance.cession c JOIN investment_policy i USING (tenant_id, policy_number))
           AS investment_cessions_left,
       (SELECT count(*) FROM reinsurance.policy_projection WHERE portfolio_code IS NULL) AS projections_without_portfolio;
