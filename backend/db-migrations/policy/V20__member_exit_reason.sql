-- WHY a loan left, and what was still owed when it did.
--
-- policy_member has carried left_on since V9, but nothing about the reason: every exit was a
-- settled claim, so there was only one reason and it went without saying. A loan can end four
-- other ways, and the difference is not bookkeeping -- a refund (task 5) and a commission
-- clawback (task 6) both branch on this value, and "the insurer paid out" and "the borrower
-- repaid" must not be treated the same way.

ALTER TABLE policy.policy_member
    ADD COLUMN exit_reason VARCHAR(30);

-- The lender's own figure at the moment the loan ended.
--
-- RECORDED, not trusted. Our own declining schedule is what values a claim; this is what the
-- lender said, kept so the two can be reconciled and so a systematic divergence shows up as
-- data rather than as an argument. Nullable because an employer scheme's member has no loan.
ALTER TABLE policy.policy_member
    ADD COLUMN outstanding_balance_at_exit NUMERIC(19,2);

ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_exit_balance_not_negative
        CHECK (outstanding_balance_at_exit IS NULL OR outstanding_balance_at_exit >= 0);

-- Every exit that exists today came from dischargeForSettledClaim, which was the only way off
-- a scheme. Backfilled before the CHECK below, which would otherwise refuse this migration on
-- any database holding an exited member -- including every dev and staging database.
UPDATE policy.policy_member
    SET exit_reason = 'CLAIM_SETTLED'
    WHERE status = 'EXITED' AND exit_reason IS NULL;

-- An exited member states why; an active one must not.
--
-- Both directions matter. A member with no reason is one no consumer can act on; a member
-- carrying a reason while still ACTIVE would read as having left to anything scanning for
-- departures.
ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_exit_reason_iff_exited
        CHECK ((status = 'EXITED') = (exit_reason IS NOT NULL));

-- Spelled out rather than left to the enum in Java. A value that reaches this column from a
-- future caller -- an exits file, a console, a migration -- and matches nothing downstream
-- would silently never refund and never claw back.
ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_exit_reason_known
        CHECK (exit_reason IS NULL OR exit_reason IN
            ('SETTLED_EARLY', 'REFINANCED', 'WRITTEN_OFF', 'CANCELLED', 'CLAIM_SETTLED'));
