-- db-migrations/product/V35__product_description.sql
-- What a product is, in a few sentences staff read when they choose it (the user, 2026-10-26): written when the product
-- is created, revised when a version is published, and shown under the product wherever one is picked -- issuing a
-- policy, opening a case, quoting, setting up a scheme, filtering a list.
--
--  * Optional everywhere (the user): a product without one shows "No description yet".
--  * product_definition.description is the one in force -- the latest written, on the product or its newest version.
--  * product_version.description is what that version was published with, kept as history: a policy on version 1
--    can still read what version 1 said.
--  * Separate from online_summary (V31), which is the customer portal's own wording (the user).

ALTER TABLE product.product_definition ADD COLUMN description TEXT;
ALTER TABLE product.product_version ADD COLUMN description TEXT;
