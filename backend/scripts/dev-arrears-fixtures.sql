-- scripts/dev-arrears-fixtures.sql
-- DEVELOPMENT FIXTURE. Never run this against staging or production.
--
-- WHY THIS EXISTS. The arrears queue is empty on a freshly seeded environment, and that is not
-- a bug: every invoice is future-dated. On the database this was written against the earliest
-- due date was 2026-10-09 with grace running to 2026-11-08, while "today" was 2026-09-14 --
-- so nothing was past due, nothing reached IN_GRACE, nothing reached OVERDUE, and
-- billing.sweep_billing_state() had nothing to open a case against. Everything downstream was
-- correct and running (the sweep exists, its cron job is active every 15 minutes, and
-- DUNNING_ESCALATION_DAYS is seeded at 7/14/21/30) with no way to see any of it work.
--
-- WHAT THIS DOES, AND WHAT IT DELIBERATELY DOES NOT DO. It moves the CLOCK, never the LOGIC.
-- It back-dates a handful of real invoices, then calls the REAL sweep and lets it open and
-- escalate the cases itself. No row is written into billing.arrears_case by hand: every case
-- the queue shows afterwards was opened by the same function that opens them in production,
-- against the same predicates -- including the in-force-policies-only guard. A fixture that
-- INSERTed cases directly would prove the screen renders and nothing about whether the platform
-- produces the rows.
--
-- WHY opened_at IS BACK-DATED TOO, in a second pass. Dunning level is time since the CASE was
-- OPENED, not time since the invoice fell due (see the escalation CASE in
-- _post-migration/configure-billing-sweep.sql, which compares now() - opened_at). So aging the
-- invoices alone yields six cases all sitting at level 1. The second pass ages the cases and
-- re-runs the sweep, so the LEVELS are still assigned by the real escalation rule rather than
-- written in.
--
-- ONE REAL CONSEQUENCE, STATED UP FRONT. The level-5 case is a policy the platform is
-- recommending for lapse. The next time anything reads billing (the Arrears screen itself will
-- do it), ArrearsNotificationSweep publishes billing.PolicyLapseRecommended for it, and policy
-- consumes that and LAPSES the contract. That is the ladder working end to end, and it is the
-- point of having a level-5 row -- but it does mean this script changes a policy's status, not
-- just billing's view of it.
--
-- Re-runnable. The sweep will not open a second case for an invoice that already has an
-- unresolved one, and re-aging an already-aged invoice is a no-op.
--
-- Usage:  docker compose -f infra/docker-compose.yml exec -T postgres \
--           psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < scripts/dev-arrears-fixtures.sql

\set ON_ERROR_STOP on

BEGIN;

-- Six ACTIVE policies, each with an unpaid invoice, chosen deterministically by policy number
-- so a re-run picks the same ones rather than dragging more of the book into arrears.
--
-- ACTIVE specifically: the sweep opens cases only for policies in force, because dunning a
-- prospect who has not accepted an offer is a customer-facing error. Picking anything else here
-- would produce a fixture the sweep silently ignores, which looks exactly like a broken sweep.
CREATE TEMP TABLE arrears_fixture_targets ON COMMIT DROP AS
SELECT p.policy_number,
       p.tenant_id,
       (SELECT pi.invoice_id
          FROM billing.premium_invoice pi
         WHERE pi.policy_number = p.policy_number
           AND pi.tenant_id = p.tenant_id
           AND pi.status IN ('DUE', 'IN_GRACE', 'OVERDUE')
         ORDER BY pi.due_date, pi.invoice_id
         LIMIT 1) AS invoice_id,
       row_number() OVER (ORDER BY p.policy_number) AS n
  FROM policy.policy p
 WHERE p.status = 'ACTIVE'
   AND EXISTS (SELECT 1
                 FROM billing.premium_invoice pi
                WHERE pi.policy_number = p.policy_number
                  AND pi.tenant_id = p.tenant_id
                  AND pi.status IN ('DUE', 'IN_GRACE', 'OVERDUE'))
 ORDER BY p.policy_number
 LIMIT 6;

-- Pass 1: age the invoices clear of their grace window, so the sweep sees them as OVERDUE.
-- 45 days past due against a 30-day grace leaves them 15 days out of grace.
UPDATE billing.premium_invoice pi
   SET due_date             = CURRENT_DATE - 45,
       grace_period_ends_at = CURRENT_DATE - 15
  FROM arrears_fixture_targets t
 WHERE pi.invoice_id = t.invoice_id;

SELECT billing.sweep_billing_state();

-- Pass 2: age the CASES, so the real escalation rule assigns levels 1 through 5.
-- Thresholds are 7/14/21/30 days, so these land either side of each one rather than on it.
UPDATE billing.arrears_case ac
   SET opened_at = now() - (t.age_days || ' days')::interval
  FROM (SELECT invoice_id,
               CASE n WHEN 1 THEN 0    -- level 1, freshly opened
                      WHEN 2 THEN 2    -- level 1, a couple of days in
                      WHEN 3 THEN 8    -- level 2
                      WHEN 4 THEN 15   -- level 3
                      WHEN 5 THEN 22   -- level 4
                      WHEN 6 THEN 31   -- level 5, lapse recommended
               END AS age_days
          FROM arrears_fixture_targets) t
 WHERE ac.invoice_id = t.invoice_id
   AND ac.resolved_at IS NULL;

SELECT billing.sweep_billing_state();

COMMIT;

-- What the queue should now hold. Read this back rather than trusting the writes above.
SELECT ac.dunning_level          AS level,
       count(*)                  AS cases,
       min(ac.policy_number)     AS example_policy
  FROM billing.arrears_case ac
 WHERE ac.resolved_at IS NULL
 GROUP BY ac.dunning_level
 ORDER BY ac.dunning_level;
