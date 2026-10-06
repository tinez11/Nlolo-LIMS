-- db-migrations/underwriting/V18__sale_channel_and_branch.sql
-- IFRS 17 I2 (classification at sale, spec §6): the case captures the channel the sale came through and the branch
-- it belongs to. Both default when the case is opened -- from the agent of record, else the product (a lender's
-- credit-life scheme is bancassurance) or DIRECT, and the branch from the agent or the staff member who opened it --
-- and both stay editable until the policy is issued, when sale_locked_at fixes them.
--
-- Controlled codes (refdata SALES_CHANNEL / BRANCH), beside the free-text `branch` and `source_of_business` of V4,
-- which stay as they were: V4 kept source_of_business free on purpose (TIRA's catalogue is still open), and the old
-- branch text is what existing proposals recorded.
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN sales_channel VARCHAR(20)
        CHECK (sales_channel IN ('AGENT','BROKER','BANCASSURANCE','DIRECT','DIGITAL')),
    ADD COLUMN branch_code VARCHAR(10),
    ADD COLUMN sale_locked_at TIMESTAMPTZ;
