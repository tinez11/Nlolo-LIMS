-- Investment contracts are never ceded and never recovered against (2026-10-09, the user's fix).
--
-- Cession held back group schemes only, so every other policy was ceded on its sum assured -- including the four
-- portfolios accounting measures as IFRS 9 investment contracts (SAV, DEP, PEN, DANN), whose "sum assured" is the
-- customer's own money. A 50% quota share took half of a fixed-term deposit as ceded cover AND as ceded premium, and a
-- deposit's death claim (the balance paid back) recovered half of it from the reinsurer (POL-CD17759E, TZS 3,000,000).
-- No reinsurer shares the return of a depositor's money; there is no insurance risk in it to cede.
--
-- The product category cannot tell them apart -- a deposit and a real endowment are both ENDOWMENT -- so the projection
-- records the portfolio policy.PolicyActivated now carries. Nullable, as V4's category is: a row written before this
-- column carries no portfolio, and NULL reads as "not known to be an investment contract". Dev rows are backfilled by
-- the one-off cleanup script, not here, because this module's migrations do not read another module's tables.
ALTER TABLE reinsurance.policy_projection
    ADD COLUMN portfolio_code VARCHAR(10);

COMMENT ON COLUMN reinsurance.policy_projection.portfolio_code IS
    'The policy''s IFRS 17 portfolio as policy.PolicyActivated reported it. SAV, DEP, PEN and DANN are investment '
    'contracts: never ceded, never recovered against. NULL means the row predates this column.';
