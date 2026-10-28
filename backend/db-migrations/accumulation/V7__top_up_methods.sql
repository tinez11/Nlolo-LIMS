-- db-migrations/accumulation/V7__top_up_methods.sql
-- How a top-up came in (2026-10-28, user decisions): the six ways money reaches the insurer -- a mobile-money request
-- to the payer's phone, or money already received by cash, bank deposit, cheque, Lipa number or TANQR -- and one person
-- records it, no second signature. A received top-up carries the method's own reference (what stops one slip being
-- credited twice) and the day the money came.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE accumulation.top_up_request ADD COLUMN IF NOT EXISTS method VARCHAR(20) NOT NULL DEFAULT 'MOBILE_MONEY';
ALTER TABLE accumulation.top_up_request ADD COLUMN IF NOT EXISTS reference VARCHAR(100);
ALTER TABLE accumulation.top_up_request ADD COLUMN IF NOT EXISTS received_on DATE;

ALTER TABLE accumulation.top_up_request DROP CONSTRAINT IF EXISTS chk_top_up_method_known;
ALTER TABLE accumulation.top_up_request
    ADD CONSTRAINT chk_top_up_method_known
    CHECK (method IN ('MOBILE_MONEY', 'CASH', 'BANK_DEPOSIT', 'CHEQUE', 'LIPA_NUMBER', 'TANQR'));

-- Money already received names its reference and its day; a mobile-money request has neither until it is paid.
ALTER TABLE accumulation.top_up_request DROP CONSTRAINT IF EXISTS chk_top_up_received_has_reference;
ALTER TABLE accumulation.top_up_request
    ADD CONSTRAINT chk_top_up_received_has_reference
    CHECK (method = 'MOBILE_MONEY' OR (reference IS NOT NULL AND received_on IS NOT NULL));

-- One slip, one credit: the same method's reference cannot be recorded twice while either still counts.
CREATE UNIQUE INDEX IF NOT EXISTS ux_top_up_reference ON accumulation.top_up_request (tenant_id, method, reference)
    WHERE reference IS NOT NULL AND status <> 'FAILED';
