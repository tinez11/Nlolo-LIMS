-- M5: adds the money-in leg's running total to premium_invoice. NOT NULL DEFAULT 0 is transient,
-- backfilling any pre-existing row (there may be some -- billing schedules generate invoices
-- ahead of need) before the default is dropped -- from that point on, every INSERT
-- (PremiumInvoice's own amountPaid = BigDecimal.ZERO field initializer) must supply the value
-- explicitly, following payment/V2's source_ref precedent exactly. Unlike source_ref's
-- empty-string placeholder, though, 0 is a perfectly legitimate PERMANENT value here (an invoice
-- genuinely unpaid so far), not a value nothing should ever keep -- so CHECK (amount_paid >= 0)
-- allows it, unlike a movement amount elsewhere on this platform which must be strictly positive.
--
-- ADD COLUMN and ADD CONSTRAINT on the partitioned parent both cascade to every existing
-- partition automatically (unlike RLS/policies, which do not) -- same behavior V2's
-- chk_premium_invoice_amount_positive already relies on for this exact table.
ALTER TABLE billing.premium_invoice ADD COLUMN amount_paid NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE billing.premium_invoice ALTER COLUMN amount_paid DROP DEFAULT;
ALTER TABLE billing.premium_invoice ADD CONSTRAINT chk_premium_invoice_amount_paid_non_negative CHECK (amount_paid >= 0);
