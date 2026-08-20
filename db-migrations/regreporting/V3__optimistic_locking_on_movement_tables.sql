-- Module: regreporting V3 -- M10 FINAL REVIEW, finding C1 (Critical): lost-update race on all
-- four movement fact tables.
--
-- WHAT WAS WRONG. All four event listeners (PolicyEventListener, ClaimsEventListener,
-- BillingEventListener, ReinsuranceEventListener) maintain their fact table by READ-MODIFY-WRITE:
-- findByTenantIdAndPeriod...(...) -> an in-memory apply* increment -> save(...). None of the four
-- entities carried @Version and no fact table carried a version column, so two concurrent
-- transactions touching the SAME row could both read the same counter, both increment it, and one
-- write would be lost -- SILENTLY. No exception, no counter, no alert; just a policy/claim/premium
-- movement that never made it into a regulatory return.
--
-- WHY IT IS NOT THEORETICAL. reinsurance_movement is ONE ROW per (tenant, period) with no product
-- grain at all (section 6 of V2 explains why), so EVERY cession for a tenant-quarter contends on
-- that single row. policy_movement/premium_movement contend per (tenant, period, product) and
-- claims_movement per (tenant, period, claim_type) -- narrower, but a busy product or claim type in
-- a quarter is exactly the high-volume case.
--
-- WHY OPTIMISTIC LOCKING AND NOT AN ATOMIC SQL UPSERT. Both close the race. Optimistic locking
-- keeps the increment arithmetic in the domain entities' apply* methods (where the gross-measure
-- semantics are documented and unit-tested) rather than duplicating it across four hand-written
-- "INSERT ... ON CONFLICT DO UPDATE SET col = col + ?" statements per measure -- policy_movement
-- alone has seven measures. Contention here is a same-key retry, not a hot global lock, so the
-- cheaper option is also the correct one. The listeners retry a bounded 3 attempts on
-- ObjectOptimisticLockingFailureException (re-fetching the row fresh each attempt), and only after
-- exhausting them do they fall through to the existing catch-all that increments
-- lifeplatform_regreporting_event_processing_failed_total and logs -- i.e. a genuinely
-- unrecoverable contention becomes ALERTABLE instead of silent.
--
-- DEFAULT 0, NOT NULL: every existing row (there are none in any deployment yet, but this must not
-- depend on that) gets version 0, which is exactly what Hibernate's first UPDATE ... WHERE
-- version = 0 expects. Same shape as regulatory_return.version, added by V2 section 3.
-- =============================================================================
ALTER TABLE regreporting.policy_movement      ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE regreporting.claims_movement      ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE regreporting.premium_movement     ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE regreporting.reinsurance_movement ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN regreporting.policy_movement.version IS
    'Optimistic lock (M10 final review C1). The listeners read-modify-write this row; without it concurrent increments lose one update silently.';
COMMENT ON COLUMN regreporting.claims_movement.version IS
    'Optimistic lock (M10 final review C1). See policy_movement.version.';
COMMENT ON COLUMN regreporting.premium_movement.version IS
    'Optimistic lock (M10 final review C1). See policy_movement.version.';
COMMENT ON COLUMN regreporting.reinsurance_movement.version IS
    'Optimistic lock (M10 final review C1). Highest-contention table of the four: one row per (tenant, period), so every cession for a tenant-quarter contends here.';

-- =============================================================================
-- return_definition.period_kind = 'ANNUAL' -- M10 FINAL REVIEW, finding C2 (Critical).
--
-- V2 section 7's CHECK admits ('QUARTERLY','ANNUAL') and ReturnGenerator validated an
-- annual-shaped period ('^\d{4}$') as legitimate -- but NO listener anywhere ever writes an
-- annual-shaped period. Every movement this module records is written as 'YYYY-Qn'. Generating
-- against an ANNUAL-kind definition therefore reported zeros for every FLOW metric (period =
-- '2026' matches no row) and a YEAR-STALE figure for every STOCK metric (period <= '2026' excludes
-- every 2026 quarter, because '2026-Q1' > '2026' lexically) -- with no exception raised.
--
-- 'ANNUAL' is retained here as a SCHEMA-LEVEL PLACEHOLDER for a future capability, not a supported
-- one: annual reporting will need real annual aggregation (roll four quarters up, or record annual
-- movements alongside quarterly ones), and which of those is right depends on TIRA's actual annual
-- return -- which is precisely what C2 has not supplied. Until then ReturnGenerator REJECTS
-- periodKind = 'ANNUAL' outright with a RegreportingValidationException rather than silently
-- computing a wrong figure. The CHECK is deliberately left admitting 'ANNUAL' (rather than
-- narrowed to 'QUARTERLY') so the eventual implementation is a code change plus a seed, with no
-- further migration needed -- but nothing may seed an ANNUAL definition before that code exists.
-- =============================================================================
COMMENT ON COLUMN regreporting.return_definition.period_kind IS
    'QUARTERLY is the only IMPLEMENTED kind. ANNUAL is a schema-level placeholder for a future capability (C2-blocked): no listener writes an annual-shaped period, so ReturnGenerator rejects an ANNUAL definition outright rather than reporting zeros/a year-stale figure. Do not seed an ANNUAL definition until annual aggregation exists.';
