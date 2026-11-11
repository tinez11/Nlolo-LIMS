-- How each premium was paid, as it was paid (2026-11-11). The channel was guessed from whether the payment named a payer:
-- a cheque, a bank deposit or cash at a branch with a payer's name read "Mobile money", and one without read "Cash (field
-- receipt)". It is now the receipt method billing records on billing.PremiumCollected, agent cash included. Rows
-- already projected keep their old two values, which stay allowed.
ALTER TABLE reporting.report_collection DROP CONSTRAINT report_collection_channel_check;
ALTER TABLE reporting.report_collection
    ADD CONSTRAINT report_collection_channel_check
        CHECK (channel IN ('MOBILE_MONEY', 'FIELD_CASH', 'AGENT_CASH', 'CASH', 'BANK_DEPOSIT', 'CHEQUE', 'LIPA_NUMBER',
                           'TANQR'));
