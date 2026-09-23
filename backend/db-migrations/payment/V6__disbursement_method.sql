-- HOW the money leaves, so that a multi-million-shilling payout need not go by the mock.
--
-- Every disbursement on this platform goes to MobileMoneyGatewayAdapter, because there has
-- only ever been one rail: PaymentGatewayPort exposes submitDisbursement and nothing else, and
-- disbursement_instruction carries no method. That rail is a mobile-money gateway against a
-- mock with NO AUTHENTICATION, which is not where a lender's claim payout belongs (spec 2.9).
--
-- EFT is not a second gateway. The instruction is RECORDED and POSTED, and finance executes
-- the transfer out of band in the bank's own portal, then confirms it here. That is the shape
-- a large payment actually takes, and it is also the honest one: the platform does not pretend
-- to have moved money it did not move.

ALTER TABLE payment.disbursement_instruction
    ADD COLUMN method VARCHAR(20) NOT NULL DEFAULT 'MOBILE_MONEY';

-- Every existing row WAS mobile money -- that was the only rail -- so the default is a true
-- statement about history rather than a convenience.
--
-- The default STAYS, and that is a decision rather than an omission. Dropping it (the first
-- version of this migration did) made every hand-written INSERT in the suite fail on a NOT NULL
-- column it had no way to know about: AppRolePrivilegesIntegrationTest, PaymentContractTest and
-- MobileMoneyCallbackIntegrationTest all seed disbursement rows in raw SQL. Those fixtures are
-- not wrong -- they predate the column -- and neither is any operational script that inserts a
-- payout row today. Java never relies on the default: DisbursementInstruction's constructors
-- always set method explicitly, so the only thing the default protects is SQL written by a human
-- who means the rail this platform has always used.

ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT chk_disbursement_method_known
        CHECK (method IN ('MOBILE_MONEY', 'EFT'));

-- AWAITING_EXECUTION: recorded and posted, waiting for a human at the bank.
--
-- Deliberately NOT reusing PENDING. PENDING means "handed to the rail, awaiting its callback",
-- and a queue of instructions nobody has sent yet must be distinguishable from a queue the
-- gateway is already working -- otherwise a payout sitting on a finance officer's desk looks
-- identical to one in flight, and nobody chases it.
-- VARCHAR(15) since V1, and 'AWAITING_EXECUTION' is eighteen characters. Widening is not
-- cosmetic: without it the CHECK below would accept the value and the INSERT would still fail,
-- which is the kind of failure that shows up as a mysterious payout error rather than as a
-- migration problem.
ALTER TABLE payment.disbursement_instruction
    ALTER COLUMN status TYPE VARCHAR(20);

ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT IF EXISTS disbursement_instruction_status_check;

ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_status_check
        CHECK (status IN ('PENDING','AWAITING_EXECUTION','IN_DOUBT','COMPLETED','FAILED'));

-- Only an EFT waits for a person, and only an EFT carries the bank's reference for the
-- transfer they actually made.
ALTER TABLE payment.disbursement_instruction
    ADD COLUMN executed_by VARCHAR(100),
    ADD COLUMN executed_at TIMESTAMPTZ;

ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT chk_disbursement_execution_complete
        CHECK ((executed_by IS NULL) = (executed_at IS NULL));

ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT chk_disbursement_only_eft_is_executed_by_hand
        CHECK (executed_by IS NULL OR method = 'EFT');

CREATE INDEX idx_disbursement_awaiting_execution
    ON payment.disbursement_instruction (tenant_id, status)
    WHERE status = 'AWAITING_EXECUTION';
