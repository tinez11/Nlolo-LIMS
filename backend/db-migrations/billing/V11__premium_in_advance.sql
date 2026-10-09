-- db-migrations/billing/V11__premium_in_advance.sql
-- Premiums in advance, with each invoice's period of cover recorded (2026-10-08, the user's decision).
--
-- Billing dated every regular instalment at the END of the period it paid for ("premium in arrears"): an annual policy
-- issued and paid on 8 Oct 2026 showed its first premium as due 8 Oct 2027 -- while cover itself only started once that
-- premium was paid. Life premiums are paid in advance. A schedule created from now on dates each instalment at the
-- START of its period; schedules already running keep the convention they were billed under (billed_in_advance false),
-- so no invoice already raised, paid or owed moves.
--
-- Either way the period an invoice pays for is now stored on it (covers_from .. covers_to), rather than worked out by
-- every reader from the due date and a convention it had to know. Backfilled here for the invoices already raised:
-- a scheduled (arrears) instalment covers the period ending the day before it fell due; a single premium's inception
-- invoice starts its cover on its due date (its end is the policy's, which billing does not hold -- left null).

ALTER TABLE billing.billing_schedule ADD COLUMN IF NOT EXISTS billed_in_advance BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE billing.premium_invoice ADD COLUMN IF NOT EXISTS covers_from DATE;
ALTER TABLE billing.premium_invoice ADD COLUMN IF NOT EXISTS covers_to DATE;
ALTER TABLE billing.premium_invoice ADD CONSTRAINT chk_premium_invoice_covers
    CHECK (covers_to IS NULL OR (covers_from IS NOT NULL AND covers_to >= covers_from));

UPDATE billing.premium_invoice i
   SET covers_from = CASE s.premium_frequency
                         WHEN 'MONTHLY'   THEN (i.due_date - INTERVAL '1 month')::date
                         WHEN 'QUARTERLY' THEN (i.due_date - INTERVAL '3 months')::date
                         WHEN 'ANNUALLY'  THEN (i.due_date - INTERVAL '1 year')::date
                     END,
       covers_to   = CASE WHEN s.premium_frequency IN ('MONTHLY', 'QUARTERLY', 'ANNUALLY')
                          THEN i.due_date - 1 END
  FROM billing.billing_schedule s
 WHERE i.billing_schedule_id = s.billing_schedule_id
   AND i.covers_from IS NULL;

-- Tolerant of a database without credit life's enrolment column (V5): there, no invoice is a file's.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'billing'
               AND table_name = 'premium_invoice' AND column_name = 'enrolment_submission_id') THEN
        UPDATE billing.premium_invoice SET covers_from = due_date
         WHERE billing_schedule_id IS NULL AND enrolment_submission_id IS NULL AND covers_from IS NULL;
    ELSE
        UPDATE billing.premium_invoice SET covers_from = due_date
         WHERE billing_schedule_id IS NULL AND covers_from IS NULL;
    END IF;
END $$;

-- The roll-forward stops at the paying end. In arrears the last instalment fell due ON it (it paid for the period
-- ending the day before); in advance the last one falls due one period BEFORE it -- an instalment dated on the paying
-- end would buy cover past the contract. Same function, the advance case added.
CREATE OR REPLACE FUNCTION billing.schedules_due_for_invoicing(horizon_months INTEGER)
RETURNS TABLE (billing_schedule_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT s.billing_schedule_id, s.tenant_id
      FROM billing.billing_schedule s
     WHERE s.status = 'ACTIVE'
       AND s.next_due_date IS NOT NULL
       AND s.next_due_date <= current_date + (horizon_months || ' months')::INTERVAL
       AND (s.premium_paying_until IS NULL
            OR s.next_due_date < s.premium_paying_until
            OR (NOT s.billed_in_advance AND s.next_due_date = s.premium_paying_until))
     ORDER BY s.next_due_date
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION billing.schedules_due_for_invoicing(INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing.schedules_due_for_invoicing(INTEGER) TO app_role;
