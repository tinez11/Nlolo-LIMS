-- What a version charges for paying in instalments rather than once a year.
--
-- PremiumFrequency divides the annual premium by 12, 4 or 1 EXACTLY, so a monthly payer and an
-- annual payer are charged the same total. No real life insurer does that. The annual payer's
-- whole premium is available to invest on day one; twelve collections cost more than one; monthly
-- business lapses more often and lapses part-paid; and distribution has already accrued commission
-- against premium that will not arrive. Market convention is monthly 5-9% above annual.
--
-- NOT a rating_table factor type, which would have reused the authoring form and the publish-time
-- rules. That table is RISK rating -- it feeds RiskProfile and therefore an underwriting decision
-- -- and a payment frequency is not a risk fact. One table meaning two things is the ambiguity
-- that produced this platform's band-string defects (V5, V9, and the quote path).
--
-- No column for ANNUALLY: it is the baseline the others load against, and a column for it could
-- only ever hold 0 or contradict itself.
--
-- NOT NULL DEFAULT 0, not nullable. "No loading" is a real pricing decision -- charge the same
-- whatever the frequency -- rather than an absence. Every one of the 128 existing versions
-- therefore gets 0 and prices EXACTLY as it does today: this migration changes no premium
-- anywhere, and a test showing otherwise is a defect in the change, not an expected difference.

ALTER TABLE product.product_version
    ADD COLUMN monthly_loading_percent   NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN quarterly_loading_percent NUMERIC(5,2) NOT NULL DEFAULT 0;

ALTER TABLE product.product_version
    ADD CONSTRAINT product_version_frequency_loading_sane
        CHECK (monthly_loading_percent   >= 0 AND monthly_loading_percent   <= 100
           AND quarterly_loading_percent >= 0 AND quarterly_loading_percent <= 100);

COMMENT ON COLUMN product.product_version.monthly_loading_percent IS
    'Percent added to the ANNUAL premium before it is divided into monthly instalments. 0 means '
    'a monthly payer is charged the same total as an annual payer.';
