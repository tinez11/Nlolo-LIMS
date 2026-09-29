-- A third origin: the policy itself.
--
-- V5 split invoices into two kinds -- one belonging to a CYCLE (billing_schedule_id) and one
-- belonging to a FILE (enrolment_submission_id) -- and asserted that exactly one is always
-- set. That was true while the only single-premium contract on the platform was a credit-life
-- master policy, whose premium genuinely arrives file by file and never from the policy.
--
-- A retail single premium is the third case, and it belongs to neither. It is one charge for
-- one contract, due when cover begins: there is no cycle, because it never recurs, and no
-- file, because nobody is enrolled against it. Its origin is the policy_number every invoice
-- already carries.
--
-- The invariant V5 was protecting is still worth keeping -- an invoice nobody can explain the
-- existence of -- so this relaxes "exactly one" to "never two" rather than dropping it. Both
-- set would still claim two origins for one charge, and that stays refused.
ALTER TABLE billing.premium_invoice
    DROP CONSTRAINT chk_premium_invoice_has_exactly_one_origin;

ALTER TABLE billing.premium_invoice
    ADD CONSTRAINT chk_premium_invoice_at_most_one_origin
        CHECK (num_nonnulls(billing_schedule_id, enrolment_submission_id) <= 1);

-- One inception invoice per policy, enforced here and not only in Java, for exactly the
-- reason V5 gives for the submission index: policy.PolicyIssued is consumed by an AFTER_COMMIT
-- listener, and an AFTER_COMMIT listener gets redelivered. A second delivery must not charge a
-- customer twice for the same contract.
--
-- due_date is in the index because premium_invoice is PARTITION BY RANGE (due_date) and
-- Postgres requires every unique index on a partitioned table to include the partition key.
-- That weakens the guarantee to "once per policy per day" unless the due date is derived from
-- something stable -- so raisePolicyInceptionInvoice derives it from the policy's OWN issue
-- date, carried on the event, never from now(). A redelivery then recomputes the identical
-- date and the index is exact. If that derivation is ever made clock-dependent, this index
-- silently stops being a guarantee.
CREATE UNIQUE INDEX ux_premium_invoice_policy_inception
    ON billing.premium_invoice (tenant_id, policy_number, due_date)
    WHERE billing_schedule_id IS NULL AND enrolment_submission_id IS NULL;
