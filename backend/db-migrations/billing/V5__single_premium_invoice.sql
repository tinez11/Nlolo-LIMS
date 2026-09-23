-- An invoice that belongs to a FILE, not to a cycle.
--
-- Every premium_invoice until now hung off a billing_schedule, because every premium until
-- now recurred. A credit-life file's premium recurs never: it is one charge for one batch of
-- borrowers, and there is no schedule for it to belong to.

ALTER TABLE billing.premium_invoice
    ALTER COLUMN billing_schedule_id DROP NOT NULL;

-- What earned it.
--
-- This is the file-to-invoice correspondence spec 2.8 asks for by name. "One file, one
-- invoice" is what a reconciliation argument with a lender is actually about, and without
-- this column the answer to "which file is this charge for" is a date guess. It is also what
-- lets a refund find the invoice that charged a departing borrower, months later.
ALTER TABLE billing.premium_invoice
    ADD COLUMN enrolment_submission_id UUID;

-- Exactly one origin, always. An invoice with neither belongs to nothing and nobody can say
-- why it exists; an invoice with both claims two different origins for one charge.
ALTER TABLE billing.premium_invoice
    ADD CONSTRAINT chk_premium_invoice_has_exactly_one_origin
        CHECK ((billing_schedule_id IS NULL) <> (enrolment_submission_id IS NULL));

-- One invoice per accepted file, enforced here and not only in Java: policy.EnrolmentAccepted
-- is consumed by an AFTER_COMMIT listener, and an AFTER_COMMIT listener gets redelivered. A
-- second delivery must not charge a lender twice for the same borrowers.
--
-- due_date is in the index because it has to be: premium_invoice is PARTITION BY RANGE
-- (due_date), and Postgres requires every unique constraint on a partitioned table to include
-- the partition key. That would ordinarily weaken the guarantee to "once per submission per
-- day" -- so raiseSinglePremiumInvoice derives the due date from the submission's own
-- accepted_at rather than from now(), which makes a redelivery recompute the identical date
-- and the index exact. If that derivation is ever changed to anything clock-dependent, this
-- index silently stops being a guarantee.
CREATE UNIQUE INDEX ux_premium_invoice_per_submission
    ON billing.premium_invoice (tenant_id, enrolment_submission_id, due_date)
    WHERE enrolment_submission_id IS NOT NULL;

-- Finds every invoice raised for a scheme without scanning by date -- the read a refund needs
-- when a borrower who enrolled eight files ago finally settles.
CREATE INDEX idx_premium_invoice_submission
    ON billing.premium_invoice (tenant_id, enrolment_submission_id)
    WHERE enrolment_submission_id IS NOT NULL;
