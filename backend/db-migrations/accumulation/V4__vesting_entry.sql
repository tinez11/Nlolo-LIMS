-- db-migrations/accumulation/V4__vesting_entry.sql
-- Product step 5 (D2): the account closes when a pension vests. Its own entry type, not MATURITY,
-- because the money does not leave -- it buys the annuity on the same policy. V1's CHECK is inline
-- and unnamed, so Postgres named it ledger_entry_entry_type_check.
ALTER TABLE accumulation.ledger_entry DROP CONSTRAINT ledger_entry_entry_type_check;
ALTER TABLE accumulation.ledger_entry ADD CONSTRAINT ledger_entry_entry_type_check CHECK (entry_type IN (
    'CONTRIBUTION','TOP_UP','TRANSFER_IN','ALLOCATION_CHARGE','POLICY_FEE','INTEREST','WITHDRAWAL',
    'SURRENDER','MATURITY','DEATH_CLAIM','FREE_LOOK_REFUND','ADJUSTMENT','REVERSAL','VESTING'));
