-- db-migrations/payment/V14__unit_linked_top_up.sql
-- Product step 6 (U2): a unit-linked top-up is a collection that is neither a premium nor a savings top-up. Its own
-- purpose, so billing (PREMIUM) and accumulation (ACCOUNT_TOP_UP) each keep ignoring money that is not theirs, and
-- unitlinked takes only UL_TOP_UP. payment_transaction is partitioned; a CHECK on the parent reaches every partition.
ALTER TABLE payment.payment_transaction DROP CONSTRAINT payment_transaction_purpose_check;
ALTER TABLE payment.payment_transaction
    ADD CONSTRAINT payment_transaction_purpose_check CHECK (purpose IN ('PREMIUM','ACCOUNT_TOP_UP','UL_TOP_UP'));
