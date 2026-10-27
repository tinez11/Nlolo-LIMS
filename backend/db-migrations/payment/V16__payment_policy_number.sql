-- db-migrations/payment/V16__payment_policy_number.sql
-- The payments register (2026-10-27): which policy a payment is for, so finance can read a list of money in and out
-- without opening each source. Carried on the request event by every module that has one; null on rows written
-- before this and on payments that belong to no single policy (an agent's commission). Added to the partitioned
-- parents, which reach every partition.
ALTER TABLE payment.payment_transaction ADD COLUMN IF NOT EXISTS policy_number VARCHAR(40);
ALTER TABLE payment.disbursement_instruction ADD COLUMN IF NOT EXISTS policy_number VARCHAR(40);

CREATE INDEX IF NOT EXISTS idx_payment_transaction_tenant_created
    ON payment.payment_transaction (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_disbursement_tenant_created
    ON payment.disbursement_instruction (tenant_id, created_at DESC);
