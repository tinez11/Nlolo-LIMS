-- db-migrations/policy/V36__unit_linked_policy.sql
-- Product step 6 (U1).
--
-- A unit-linked surrender is priced FORWARD, at the first unit price after it is approved, so its request carries
-- no quoted value (plan R11): there is no figure to quote that would not be a price already known. The console
-- shows an indicative value from the units instead, labelled as such. The CHECK still refuses a zero or negative
-- quote on every other surrender, since a NULL passes it.
ALTER TABLE policy.surrender_request ALTER COLUMN quoted_value_amount DROP NOT NULL;

-- Never written, and superseded by unitlinked's unit ledger: holdings are the sum of its entries (spec §1).
-- No row exists in the dev database (checked 2026-10-05).
DROP TABLE policy.fund_holding;
