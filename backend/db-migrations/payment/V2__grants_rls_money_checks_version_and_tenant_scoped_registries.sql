-- db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql
-- M5 (payment). V1 is already applied elsewhere and must never be edited -- this file is
-- additive. V1 shipped four defects, all fixed here, all the same bug classes M1/M2/M3/M4
-- each found and fixed for their own schemas.

-- =============================================================================
-- 1. GRANTS. V1 has ZERO GRANT statements of any kind -- app_role lacks even USAGE on the
-- schema, so the payment module is non-functional at runtime as shipped. This is the exact
-- bug billing/V2 was written to fix for billing ("V1 ... had zero GRANT statements anywhere
-- in the file -- the exact bug class M1/M2/M3 each found and fixed for every other schema").
-- =============================================================================
GRANT USAGE ON SCHEMA payment TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA payment TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA payment GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- NOTE ON THE APPEND-ONLY REVOKE, DELIBERATELY NOT RE-ISSUED HERE.
-- V1:36 and V1:71 carry `REVOKE UPDATE, DELETE ... FROM app_role` on both ledgers, and
-- docs/06-database-schema.md's WORM list names them. Those REVOKEs were no-ops when they ran
-- (nothing had been granted yet), and this migration deliberately does NOT re-issue them:
-- payment_transaction and disbursement_instruction are instruction records with a real
-- lifecycle (status PENDING -> CONFIRMED/COMPLETED/FAILED, gateway_reference filled in on
-- callback, batch_id set on batching), NOT movement ledgers like policyloan.loan_transaction
-- where every row is immutable by nature. Shipping both an append-only REVOKE and a mutable
-- status column is self-contradictory, and the contradiction was resolved in favour of the
-- lifecycle. The independent, genuinely append-only audit trail is audit.audit_log, which
-- records every payment.* event generically. docs/06-database-schema.md is corrected in the
-- same commit so the doc stops asserting otherwise.

-- =============================================================================
-- 2. RLS on all five tables and both ledgers' hand-written partitions.
-- V1:91-95 justifies omitting RLS entirely because "`payment` has no synchronous external
-- callers at all". That premise is false: api/openapi/openapi-payment.yaml declares three
-- token-authenticated GET endpoints, and one of them (/payments/status-by-key) looks up a
-- CLIENT-SUPPLIED opaque string against a globally-unique registry -- a direct cross-tenant
-- read without RLS. Postgres does NOT cascade ENABLE ROW LEVEL SECURITY or policies from a
-- partitioned parent to its partitions (established empirically in M3 Task 1 and re-confirmed
-- in M3's final review), so both partitions of both ledgers need it applied explicitly.
-- =============================================================================
ALTER TABLE payment.payment_transaction ENABLE ROW LEVEL SECURITY;
CREATE POLICY payment_transaction_tenant_isolation ON payment.payment_transaction
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE payment.payment_transaction_2026_08 ENABLE ROW LEVEL SECURITY;
CREATE POLICY payment_transaction_2026_08_tenant_isolation ON payment.payment_transaction_2026_08
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE payment.payment_transaction_2026_09 ENABLE ROW LEVEL SECURITY;
CREATE POLICY payment_transaction_2026_09_tenant_isolation ON payment.payment_transaction_2026_09
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE payment.disbursement_instruction ENABLE ROW LEVEL SECURITY;
CREATE POLICY disbursement_instruction_tenant_isolation ON payment.disbursement_instruction
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE payment.disbursement_instruction_2026_08 ENABLE ROW LEVEL SECURITY;
CREATE POLICY disbursement_instruction_2026_08_tenant_isolation ON payment.disbursement_instruction_2026_08
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE payment.disbursement_instruction_2026_09 ENABLE ROW LEVEL SECURITY;
CREATE POLICY disbursement_instruction_2026_09_tenant_isolation ON payment.disbursement_instruction_2026_09
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE payment.payout_batch ENABLE ROW LEVEL SECURITY;
CREATE POLICY payout_batch_tenant_isolation ON payment.payout_batch
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 3. Tenant-scope both idempotency registries. THE WORST DEFECT IN V1.
-- Both registries are `idempotency_key VARCHAR(100) PRIMARY KEY` with no tenant_id column at
-- all. The registry IS the dedup gate: tenant A inserting key K makes tenant B's genuine,
-- unrelated PaymentRequested carrying key K get DROPPED as a "safe duplicate" -- a silently
-- lost payment across a tenant boundary. This is the same class of bug billing/V2 fixed
-- ("V1's ux_field_receipt_idempotency has no tenant_id -- two different tenants' agents could
-- collide on the same client-generated key"), M1 fixed for party and M2 for product, but with
-- a strictly worse consequence here: not a rejected insert, a vanished payment.
-- Both registries are empty (payment has never run), so a straight ADD + PK swap is safe.
-- =============================================================================
ALTER TABLE payment.payment_transaction_idempotency_registry
    ADD COLUMN tenant_id UUID NOT NULL;
ALTER TABLE payment.payment_transaction_idempotency_registry
    DROP CONSTRAINT payment_transaction_idempotency_registry_pkey;
ALTER TABLE payment.payment_transaction_idempotency_registry
    ADD PRIMARY KEY (tenant_id, idempotency_key);
ALTER TABLE payment.payment_transaction_idempotency_registry ENABLE ROW LEVEL SECURITY;
CREATE POLICY payment_idem_registry_tenant_isolation ON payment.payment_transaction_idempotency_registry
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE payment.disbursement_idempotency_registry
    ADD COLUMN tenant_id UUID NOT NULL;
ALTER TABLE payment.disbursement_idempotency_registry
    DROP CONSTRAINT disbursement_idempotency_registry_pkey;
ALTER TABLE payment.disbursement_idempotency_registry
    ADD PRIMARY KEY (tenant_id, idempotency_key);
ALTER TABLE payment.disbursement_idempotency_registry ENABLE ROW LEVEL SECURITY;
CREATE POLICY disbursement_idem_registry_tenant_isolation ON payment.disbursement_idempotency_registry
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- The registries stay NON-PARTITIONED, and that is the entire point -- do not "tidy" this.
-- docs/06-database-schema.md:12 records why: Postgres requires a partitioned table's unique
-- constraint to include the partition key, so the original `UNIQUE (idempotency_key)` on the
-- partitioned ledgers failed outright, and widening it to `(idempotency_key, created_at)`
-- "would have 'fixed' the error message but silently broken the actual guarantee -- a replayed
-- event landing under a different created_at would no longer be caught as a duplicate."

-- =============================================================================
-- 4. Money CHECK constraints. V1 has none on either ledger, so negative and zero amounts are
-- accepted at the storage layer. A negative amount was a real, fixed Critical in M3
-- (policyloan's MoneyDto accepted a signed amount that inverted into a cash-value inflation
-- exploit), and policy/V2, policyloan/V3 and billing/V2 each added these afterwards.
-- A CHECK added to a partitioned parent IS recursively applied to existing partitions and
-- inherited by every future one, so one statement per table covers all partitions.
-- =============================================================================
ALTER TABLE payment.payment_transaction
    ADD CONSTRAINT chk_payment_transaction_amount_positive CHECK (amount > 0);
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT chk_disbursement_amount_positive CHECK (amount > 0);

-- =============================================================================
-- 5. Optimistic locking. V1 has no `version` column on any table, yet concurrent gateway
-- callbacks racing the same transaction row is the normal case for an at-least-once rail.
-- M3's final review added the platform-wide OptimisticLockingFailureException -> 409 mapping
-- (GlobalExceptionHandler), so a lost version race now surfaces correctly rather than as a 500.
-- =============================================================================
ALTER TABLE payment.payment_transaction ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment.disbursement_instruction ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment.payout_batch ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- =============================================================================
-- 6. source_ref: the originating aggregate's id, so a confirmation can be correlated back to
-- the loan/invoice that asked for the money. V1 stores only payer_ref/payee_ref (opaque
-- gateway destinations) and `purpose`, with no column for the requesting domain object -- so
-- today a DisbursementCompleted could only be matched back by reverse-engineering the
-- idempotency key, which is implicit and fragile. Stored explicitly instead, and echoed in the
-- outbound event payload (Task 5).
-- NOT NULL with a transient DEFAULT purely to satisfy the constraint against any pre-existing
-- row (there are none -- payment has never run); the default is dropped immediately so every
-- future INSERT must supply a real value.
-- =============================================================================
ALTER TABLE payment.payment_transaction ADD COLUMN source_ref VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE payment.payment_transaction ALTER COLUMN source_ref DROP DEFAULT;
ALTER TABLE payment.disbursement_instruction ADD COLUMN source_ref VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE payment.disbursement_instruction ALTER COLUMN source_ref DROP DEFAULT;

-- =============================================================================
-- 7. Close the batch_type/purpose mismatch. payout_batch.batch_type allows 'DIVIDEND_RUN' but
-- disbursement_instruction.purpose has no dividend value, so a dividend run's member
-- instructions have no legal purpose. Adding the missing enum value rather than removing the
-- batch type, since Deliverable 3 named the batch type deliberately.
-- Postgres cannot ALTER an existing CHECK, so it is dropped and recreated with the added value.
-- =============================================================================
ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT disbursement_instruction_purpose_check;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_purpose_check CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT',
         'MATURITY_PAYOUT','DIVIDEND_PAYOUT'));

-- =============================================================================
-- 8. Index supporting the webhook's lookup path (Task 8 resolves a callback to its row by
-- gateway_reference). Not unique: a gateway may legitimately reuse a reference across tenants.
-- =============================================================================
CREATE INDEX idx_disbursement_gateway_reference ON payment.disbursement_instruction (gateway_reference)
    WHERE gateway_reference IS NOT NULL;
CREATE INDEX idx_payment_transaction_gateway_reference ON payment.payment_transaction (gateway_reference)
    WHERE gateway_reference IS NOT NULL;
