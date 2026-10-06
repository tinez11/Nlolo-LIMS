-- db-migrations/distribution/V6__commission_withholding.sql
-- IFRS 17 I3b (posting guide A-05, user decision 7): withholding tax taken from a commission payout at the rate in
-- force when the payout is requested -- the accounting policy register's COMMISSION_WITHHOLDING_RATE. No rate in force
-- (or NONE): nothing withheld, never a default. The agent is paid total_amount - withheld_amount.

ALTER TABLE distribution.commission_statement
    ADD COLUMN withholding_rate NUMERIC(7,6) CHECK (withholding_rate > 0 AND withholding_rate <= 1),
    ADD COLUMN withheld_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (withheld_amount >= 0);
