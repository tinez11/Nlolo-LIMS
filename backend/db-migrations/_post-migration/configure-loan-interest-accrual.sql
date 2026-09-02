-- Run by the CD pipeline immediately after Flyway migrations complete, same convention as
-- configure-billing-sweep.sql, configure-commission-close.sql and configure-pg-partman.sql
-- (never as a Flyway migration itself). Requires db-migrations/policyloan/V5.
--
-- WHAT THIS CLOSES: policy loan interest was never accrued. The rate was resolved at
-- origination, persisted effective-dated into policyloan.loan_interest_term, exposed as
-- LoanView.currentInterestRate, and folded into the outstanding balance by
-- PolicyLoanApiImpl.computeOutstandingBalance -- but no producer ever wrote an
-- INTEREST_ACCRUAL ledger entry, so every outstanding balance stayed flat forever and
-- docs/01-domain-map.md:224's Forced Lapse ("loan balance plus interest exceeds cash value")
-- was unreachable. See db-migrations/policyloan/V5__loan_interest_accrual.sql.
--
-- WHY THIS IS pg_cron AND NOT A JAVA @Scheduled METHOD: accrual is a CROSS-TENANT sweep, and a
-- Java scheduler thread has no TenantContext, so every tenant-scoped RLS policy evaluates
-- current_setting('app.current_tenant_id', true) as NULL and the thread sees ZERO rows.
-- Documented on this platform at Application.java:7-9, PolicyApiImpl.java:~300, and
-- configure-commission-close.sql. There is also no tenant-directory table to iterate.
-- SECURITY DEFINER means this executes with the privileges of the role that OWNS it (the
-- migration-applying role, which owns every table in this schema), bypassing RLS by virtue of
-- table ownership. app_role is never granted EXECUTE (see the REVOKE below) and never bypasses
-- RLS at any point -- this is infrastructure automation, not a request-path privilege escalation.
--
-- THE SPLIT THIS MAKES is the same honest one billing and distribution make: guaranteed-on-time
-- STATE lives here in SQL; anything needing per-tenant Java stays in the application.
-- Concretely, this function deliberately does NOT evaluate forced lapse and does NOT publish a
-- domain event:
--   * Forced lapse is "balance + interest > cash value", and cash value lives in
--     policy.policy_account, which docs/01-domain-map.md:46 says policyloan reads "via its
--     public API, never its tables directly". Evaluating it here would mean reaching across a
--     schema this module is forbidden to read. Instead this function sets
--     policy_loan.forced_lapse_review_due_at, and PolicyLoanApiImpl.evaluateForcedLapse does
--     the compare through PolicyApi inside a real tenant context.
--   * Events are published by Spring's ApplicationEventPublisher, which does not exist in a
--     Postgres backend session. finaccounting therefore posts no journal entry for accrued
--     interest yet -- a KNOWN, DELIBERATE gap recorded in PostingRule's javadoc rather than
--     silently papered over, because posting interest income needs a 4xxx INCOME account and
--     PostingRule states no 4xxx account is seeded on purpose (LRC release is C1-blocked,
--     Actuarial). Interest is correct in the loan ledger, absent from the general ledger.
CREATE OR REPLACE FUNCTION policyloan.accrue_loan_interest() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $function$
DECLARE
    v_loan          RECORD;
    v_accrue_from   DATE;
    v_days          INTEGER;
    v_balance       NUMERIC(19,2);
    v_rate          NUMERIC(7,4);
    v_interest      NUMERIC(19,2);
BEGIN
    -- Only a loan whose money has actually left the building accrues. RESERVED_PENDING_
    -- ORIGINATION / ORIGINATED / DISBURSEMENT_REQUESTED have not been paid out; SETTLED,
    -- FORCED_LAPSE_TRIGGERED and DISBURSEMENT_FAILED are terminal. This mirrors
    -- PolicyLoanApiImpl.recordRepayment's own DISBURSED-or-REPAYING precondition: the same two
    -- statuses that can take a repayment are the two that can take an accrual.
    FOR v_loan IN
        SELECT loan_id, tenant_id, principal_amount, principal_currency
          FROM policyloan.policy_loan
         WHERE status IN ('DISBURSED', 'REPAYING')
    LOOP
        -- Accrue from the day interest was last accrued, or -- for a loan that has never
        -- accrued -- from the day it was disbursed. Deriving this from the LEDGER rather than a
        -- denormalized last_accrued_on column is deliberate: V1's own header calls
        -- loan_transaction the "authoritative source for outstanding balance (never the
        -- schedule)", and a second copy of "when did we last accrue" is a second thing that can
        -- drift from it.
        SELECT MAX(occurred_at)::date INTO v_accrue_from
          FROM policyloan.loan_transaction
         WHERE loan_id = v_loan.loan_id
           AND transaction_type = 'INTEREST_ACCRUAL';

        IF v_accrue_from IS NULL THEN
            SELECT MAX(occurred_at)::date INTO v_accrue_from
              FROM policyloan.loan_transaction
             WHERE loan_id = v_loan.loan_id
               AND transaction_type = 'DISBURSEMENT';
        END IF;

        -- No DISBURSEMENT entry means markDisbursed never ran for this loan, so there is no
        -- defensible date to accrue from. Skip rather than guess (e.g. created_at), which would
        -- charge interest for a period during which the policyholder held no money.
        CONTINUE WHEN v_accrue_from IS NULL;

        v_days := CURRENT_DATE - v_accrue_from;

        -- IDEMPOTENCE AND CATCH-UP IN ONE PROPERTY, which is why the window is a day COUNT and
        -- not a fixed "one day's interest". Re-running on the same day gives v_days = 0 and
        -- writes nothing, so a second tick (or a manual run) is a no-op by construction. And if
        -- the job does not run for three days -- container down, cron disabled, a failing tick
        -- nobody was alerted to, since pg_cron_job_failed_total has no producer on this platform
        -- -- the next successful run accrues all three days instead of silently forgiving two.
        -- A fixed one-day accrual would have under-accrued forever with no error anywhere.
        CONTINUE WHEN v_days <= 0;

        -- MUST STAY IN SYNC WITH PolicyLoanApiImpl.computeOutstandingBalance. That method
        -- starts from principal_amount and folds the ledger: REPAYMENT and SETTLEMENT subtract,
        -- INTEREST_ACCRUAL adds, DISBURSEMENT and REVERSAL are ignored (the principal already
        -- reflects the disbursement). Two implementations of one balance rule is a real drift
        -- risk, accepted here because the alternative -- Java computing it -- is the
        -- cross-tenant RLS problem this whole file exists to avoid.
        SELECT v_loan.principal_amount + COALESCE(SUM(
                   CASE transaction_type
                       WHEN 'INTEREST_ACCRUAL' THEN amount
                       WHEN 'REPAYMENT'        THEN -amount
                       WHEN 'SETTLEMENT'       THEN -amount
                       ELSE 0
                   END), 0)
          INTO v_balance
          FROM policyloan.loan_transaction
         WHERE loan_id = v_loan.loan_id;

        -- A fully-repaid loan still sitting in REPAYING (recordRepayment only marks SETTLED
        -- when the balance hits zero on ITS path) must not accrue on a zero or negative balance.
        CONTINUE WHEN v_balance IS NULL OR v_balance <= 0;

        -- The per-loan effective-dated rate, which is the entire point of L1's
        -- loan_interest_term child table: "locked at origination" collapses to one row for the
        -- loan's life, "floating" records each change, and this query is correct either way.
        -- Reading refdata's TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE here instead would silently
        -- re-rate every existing loan the moment Actuarial changes the parameter.
        SELECT rate INTO v_rate
          FROM policyloan.loan_interest_term
         WHERE loan_id = v_loan.loan_id
           AND effective_from <= CURRENT_DATE
         ORDER BY effective_from DESC
         LIMIT 1;

        CONTINUE WHEN v_rate IS NULL OR v_rate <= 0;

        -- PLACEHOLDER ACCRUAL CONVENTION, pending Actuarial sign-off -- the same treatment and
        -- the same standing caveat as the rate value itself (refdata V2:
        -- "PLACEHOLDER, pending Actuarial sign-off") and as PostingRule's account mappings.
        -- What is invented here, explicitly:
        --   * SIMPLE interest across the accrual window (v_days), on the balance as at this
        --     run. Because the balance already includes previously accrued interest, interest
        --     compounds BETWEEN runs (daily in normal operation) while staying simple WITHIN a
        --     catch-up window. Capitalizing unpaid interest into the loan balance is
        --     conventional for policy loans; the compounding FREQUENCY is the actuarial choice.
        --   * ACTUAL/365 day count. Not 30/360, not actual/actual -- and leap years therefore
        --     accrue 366/365 of a nominal year.
        --   * rate is a PERCENT (refdata seeds '12.0' meaning 12%), hence the division by 100.
        --   * HALF-UP to 2 decimal places, matching NUMERIC(19,2) on the ledger column.
        v_interest := ROUND(v_balance * (v_rate / 100.0) * (v_days::numeric / 365.0), 2);

        -- Sub-cent accrual: skip, do NOT write. V3's chk_loan_transaction_amount_positive is
        -- CHECK (amount > 0), so a rounded-to-zero row would abort the whole sweep for every
        -- remaining tenant, not just this loan. Skipping also leaves v_accrue_from unmoved, so
        -- the fraction is not lost -- it accumulates until a later run's wider window rounds to
        -- at least a cent.
        CONTINUE WHEN v_interest < 0.01;

        -- occurred_at defaults to now(), which is what makes the partition routing correct and
        -- what the next run reads back as v_accrue_from. The reference string records the
        -- window actually charged, so a statement can be reconciled day by day and a catch-up
        -- run is visibly a catch-up rather than an unexplained large accrual.
        INSERT INTO policyloan.loan_transaction (tenant_id, loan_id, transaction_type, amount, currency, reference)
        VALUES (v_loan.tenant_id, v_loan.loan_id, 'INTEREST_ACCRUAL', v_interest, v_loan.principal_currency,
                'ACCRUAL ' || (v_accrue_from + 1) || '..' || CURRENT_DATE || ' @ ' || v_rate || '% actual/365');

        -- Hand the cross-module half back to per-tenant Java (see this file's header and V5's
        -- column comment). Setting this unconditionally on every accrual is correct: the
        -- balance just grew, so a shortfall that did not exist before may exist now, and
        -- evaluateForcedLapse is cheap and idempotent when it does not.
        --
        -- The version bump is deliberate, not decorative. policy_loan.version backs JPA's
        -- @Version on PolicyLoan. Without it, an entity loaded before this UPDATE and saved
        -- after it would carry the same version, so Hibernate's optimistic lock would pass and
        -- SILENTLY overwrite forced_lapse_review_due_at back to NULL -- dropping a pending
        -- forced-lapse review with no error anywhere. Bumping it makes that write fail loudly
        -- with an optimistic-locking failure instead, which the caller retries.
        UPDATE policyloan.policy_loan
           SET forced_lapse_review_due_at = now(),
               version = version + 1,
               updated_at = now(),
               updated_by = 'accrue_loan_interest'
         WHERE loan_id = v_loan.loan_id;
    END LOOP;
END;
$function$;

-- Postgres grants EXECUTE on a newly created function to PUBLIC by default -- unlike tables,
-- where a bare CREATE grants nothing. Without this REVOKE, app_role could call this SECURITY
-- DEFINER function directly despite being NOSUPERUSER NOBYPASSRLS, which is exactly the
-- request-path privilege escalation the header above says must never be possible. The real
-- caller is unaffected: pg_cron records each job's scheduling role in cron.job.username, which
-- is the migration-applying role ('postgres'), never app_role. This is the same gap M7 found
-- and fixed in configure-billing-sweep.sql after it had been open since M4.
REVOKE EXECUTE ON FUNCTION policyloan.accrue_loan_interest() FROM PUBLIC;

-- Daily at 03:00. SELECT, not CALL: this is a FUNCTION, and Postgres answers CALL on a function
-- with "is not a procedure" -- the exact bug that left billing's sweep failing on every
-- 15-minute tick from M4 until M7, unnoticed because pg_cron_job_failed_total has no producer.
-- LoanInterestAccrualSweepPsqlTest.theScheduledCommandStringActuallyExecutes parses and runs
-- this very string so a future edit back to CALL fails in the suite instead of in production.
--
-- 03:00 deconflicts with the three jobs already registered on this platform: billing-sweep
-- (*/15), commission-close (01:00) and pg-partman-maintenance (02:00). Ordering against partman
-- matters: partman creates next month's loan_transaction partition at 02:00, so a month-boundary
-- accrual at 03:00 always has a partition to land in.
SELECT cron.schedule('loan-interest-accrual', '0 3 * * *', $$SELECT policyloan.accrue_loan_interest()$$);
