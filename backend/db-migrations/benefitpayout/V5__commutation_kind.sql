-- db-migrations/benefitpayout/V5__commutation_kind.sql
-- Product step 5 (D2): a pension's lump sum at vesting. Its own kind, never an authored row: the
-- annuity module schedules it once, on the vesting date, out of the closed account's balance.
ALTER TABLE benefitpayout.payout_instalment DROP CONSTRAINT payout_instalment_kind_check;
ALTER TABLE benefitpayout.payout_instalment ADD CONSTRAINT payout_instalment_kind_check
    CHECK (kind IN ('SURVIVAL','MATURITY','INCOME','RETURN_OF_PREMIUM','ANNUITY','COMMUTATION'));

-- A withholding rule may name the lump sum too: whether it is taxed is the law's to say, and the
-- rule is data (D1 spec Q8). V3's CHECK is inline and unnamed, so Postgres named it
-- withholding_rule_payout_kinds_check.
ALTER TABLE benefitpayout.withholding_rule DROP CONSTRAINT withholding_rule_payout_kinds_check;
ALTER TABLE benefitpayout.withholding_rule ADD CONSTRAINT withholding_rule_payout_kinds_check
    CHECK (payout_kinds ~ '^(SURVIVAL|MATURITY|INCOME|RETURN_OF_PREMIUM|ANNUITY|COMMUTATION)(,(SURVIVAL|MATURITY|INCOME|RETURN_OF_PREMIUM|ANNUITY|COMMUTATION))*$');
