-- db-migrations/accumulation/V5__term_accounts.sql
-- A term account (2026-10-21): contributions arrive as on any savings account (V1's ledger, unchanged), at a rate the
-- case named inside its version's band, and nothing leaves until the policy matures -- then the whole balance is
-- paid out. One row per term-account policy, written when the account opens; its presence is what marks the account
-- a term account here, the way deposit_period's marks a fixed-term deposit.
--
-- status: RUNNING until the maturity date; MATURED once the balance has been sent to a payee; AWAITING_PAYEE when it
-- matured with nobody to pay (no number collected from), or its payment failed and the money came back.
CREATE TABLE accumulation.term_account (
    -- Its own id, as a deposit period has: what V2's request_key names when finance pays a waiting one out.
    term_account_id   UUID PRIMARY KEY,
    policy_number     VARCHAR(20) NOT NULL UNIQUE REFERENCES accumulation.account(policy_number),
    tenant_id         UUID NOT NULL,
    -- The policy's rate, % a year, locked when the account opened: the case's, never a declaration's.
    rate_percent      NUMERIC(7,4) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    maturity_date     DATE NOT NULL,
    status            VARCHAR(14) NOT NULL DEFAULT 'RUNNING'
                      CHECK (status IN ('RUNNING','MATURED','AWAITING_PAYEE')),
    -- The number the first contribution was collected from; where the maturity goes. Null for a cash receipt, and then
    -- a matured account waits for finance to record a payee.
    default_payee_ref VARCHAR(200),
    matured_on        DATE,
    payout_attempts   INTEGER NOT NULL DEFAULT 0 CHECK (payout_attempts >= 0),
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT term_account_matured_shape CHECK ((status = 'RUNNING') = (matured_on IS NULL))
);

-- The rate is the record of what was sold: fixed once written, whatever the product later declares or republishes.
CREATE OR REPLACE FUNCTION accumulation.term_account_terms_fixed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.term_account_id, NEW.policy_number, NEW.rate_percent, NEW.maturity_date)
       IS DISTINCT FROM (OLD.term_account_id, OLD.policy_number, OLD.rate_percent, OLD.maturity_date) THEN
        RAISE EXCEPTION 'term account % is the record of its rate: its terms cannot change', OLD.policy_number;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER term_account_terms_fixed BEFORE UPDATE ON accumulation.term_account
    FOR EACH ROW EXECUTE FUNCTION accumulation.term_account_terms_fixed();

-- The maturity run's selector: running term accounts whose day has come, on an account still open (one closed by a
-- death, a free-look or exhaustion has nothing to pay), across tenants. Ids only, as deposits_due() is.
CREATE OR REPLACE FUNCTION accumulation.term_accounts_due()
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT t.policy_number, t.tenant_id FROM accumulation.term_account t
      JOIN accumulation.account a ON a.policy_number = t.policy_number
     WHERE t.status = 'RUNNING' AND t.maturity_date <= current_date AND a.status = 'OPEN'
     ORDER BY t.maturity_date
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION accumulation.term_accounts_due() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION accumulation.term_accounts_due() TO app_role;

ALTER TABLE accumulation.term_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY term_account_tenant_isolation ON accumulation.term_account
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON accumulation.term_account TO app_role;
