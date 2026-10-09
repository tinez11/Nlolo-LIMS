-- db-migrations/product/V31__online_listing.sql
-- What customers see of a product in the portal (2026-10-08, the customer portal design step 5): whether it is offered
-- online at all, a sentence on what it is for, and its key benefits.
--
-- On the product rather than on each version: the design note said per version, but a listing written against one
-- version would vanish at the next price change, and what a product is FOR does not change with its rates. Off by
-- default -- no product appears to customers until somebody decides it should and says what it is.

ALTER TABLE product.product_definition ADD COLUMN IF NOT EXISTS available_online BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product.product_definition ADD COLUMN IF NOT EXISTS online_summary TEXT;
-- One benefit per line.
ALTER TABLE product.product_definition ADD COLUMN IF NOT EXISTS online_benefits TEXT;

ALTER TABLE product.product_definition ADD CONSTRAINT chk_product_online_listing_described
    CHECK (NOT available_online OR (online_summary IS NOT NULL AND length(trim(online_summary)) > 0));
