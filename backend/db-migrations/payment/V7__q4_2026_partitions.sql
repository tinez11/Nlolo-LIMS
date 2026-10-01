-- db-migrations/payment/V7__q4_2026_partitions.sql
-- Add monthly partitions for payment_transaction and disbursement_instruction, Q4 2026 + Q1 2027.
-- V1 created partitions through 2026-09-30. Running on or after 2026-10-01, any INSERT fails
-- with "no partition of relation found for row".
CREATE TABLE payment.payment_transaction_2026_10 PARTITION OF payment.payment_transaction
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE payment.payment_transaction_2026_11 PARTITION OF payment.payment_transaction
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE payment.payment_transaction_2026_12 PARTITION OF payment.payment_transaction
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE payment.payment_transaction_2027_01 PARTITION OF payment.payment_transaction
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE payment.payment_transaction_2027_02 PARTITION OF payment.payment_transaction
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE payment.payment_transaction_2027_03 PARTITION OF payment.payment_transaction
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE payment.disbursement_instruction_2026_10 PARTITION OF payment.disbursement_instruction
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE payment.disbursement_instruction_2026_11 PARTITION OF payment.disbursement_instruction
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE payment.disbursement_instruction_2026_12 PARTITION OF payment.disbursement_instruction
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE payment.disbursement_instruction_2027_01 PARTITION OF payment.disbursement_instruction
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE payment.disbursement_instruction_2027_02 PARTITION OF payment.disbursement_instruction
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE payment.disbursement_instruction_2027_03 PARTITION OF payment.disbursement_instruction
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
