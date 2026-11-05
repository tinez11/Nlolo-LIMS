-- db-migrations/payment/V26__receipt_number.sql
-- Every amount of money received gets one numbered receipt (receipts step 1, 2026-11-05; TIRA Insurance Regulations
-- 2009, Code B.5(b): "acknowledge receipt ... of all monies received"). A payment is numbered the moment it becomes
-- CONFIRMED -- a mobile-money callback, a second person's approval, a top-up recorded as received -- by a trigger, so no
-- road to CONFIRMED can skip it. RC-<year>-<six digits>, a sequence per tenant per year, never reused: a reversed
-- payment keeps its number.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS payment.payment_transaction ADD COLUMN IF NOT EXISTS receipt_number VARCHAR(20);
-- For finding a receipt by its number. Not UNIQUE: payment_transaction is partitioned by created_at, and a unique
-- index on a partitioned table must carry the partition key. The counter below is what makes a number given once.
CREATE INDEX IF NOT EXISTS idx_payment_receipt_number
    ON payment.payment_transaction (tenant_id, receipt_number) WHERE receipt_number IS NOT NULL;

-- The last number given, per tenant per year. Written only by the trigger below, which runs as its owner.
CREATE TABLE IF NOT EXISTS payment.receipt_counter (
    tenant_id   UUID     NOT NULL,
    year        SMALLINT NOT NULL,
    last_number INTEGER  NOT NULL,
    PRIMARY KEY (tenant_id, year)
);
ALTER TABLE payment.receipt_counter ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS receipt_counter_tenant_isolation ON payment.receipt_counter;
CREATE POLICY receipt_counter_tenant_isolation ON payment.receipt_counter
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- The next number for a tenant in a year: one row per tenant-year, locked by the upsert, so two confirmations at once
-- get two numbers and never the same one.
CREATE OR REPLACE FUNCTION payment.next_receipt_number(p_tenant UUID, p_year SMALLINT)
RETURNS VARCHAR LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE n INTEGER;
BEGIN
    INSERT INTO payment.receipt_counter (tenant_id, year, last_number) VALUES (p_tenant, p_year, 1)
    ON CONFLICT (tenant_id, year) DO UPDATE SET last_number = payment.receipt_counter.last_number + 1
    RETURNING last_number INTO n;
    RETURN 'RC-' || p_year || '-' || lpad(n::text, 6, '0');
END;
$$;
REVOKE EXECUTE ON FUNCTION payment.next_receipt_number(UUID, SMALLINT) FROM PUBLIC;

CREATE OR REPLACE FUNCTION payment.number_confirmed_receipt() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
    IF NEW.status = 'CONFIRMED' AND NEW.receipt_number IS NULL THEN
        -- The civil year in Dar es Salaam: a receipt confirmed at 01:00 on 1 January is that year's.
        NEW.receipt_number := payment.next_receipt_number(NEW.tenant_id,
            EXTRACT(YEAR FROM (now() AT TIME ZONE 'Africa/Dar_es_Salaam'))::SMALLINT);
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_payment_receipt_number ON payment.payment_transaction;
CREATE TRIGGER trg_payment_receipt_number BEFORE INSERT OR UPDATE OF status ON payment.payment_transaction
    FOR EACH ROW EXECUTE FUNCTION payment.number_confirmed_receipt();

-- Money already received before this: numbered in the order it was confirmed, each in its own year.
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT payment_transaction_id, tenant_id, COALESCE(decided_at, created_at) AS at
               FROM payment.payment_transaction
              WHERE status IN ('CONFIRMED', 'REVERSED') AND receipt_number IS NULL
              ORDER BY COALESCE(decided_at, created_at), payment_transaction_id
    LOOP
        UPDATE payment.payment_transaction
           SET receipt_number = payment.next_receipt_number(r.tenant_id,
                   EXTRACT(YEAR FROM (r.at AT TIME ZONE 'Africa/Dar_es_Salaam'))::SMALLINT)
         WHERE payment_transaction_id = r.payment_transaction_id;
    END LOOP;
END;
$$;
