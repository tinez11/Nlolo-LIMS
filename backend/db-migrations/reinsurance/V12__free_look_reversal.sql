-- db-migrations/reinsurance/V12__free_look_reversal.sql
-- A free-look cancellation after a month's bordereau has posted (2026-11-08). The contract was never on risk, so what
-- the reinsurer was charged for it -- ceded premium and its commission -- comes back on the treaty's next bordereau, as
-- FREE_LOOK_REVERSAL lines carrying the amounts taken back (positive, as every amount here). The bordereau keeps its
-- charge as before and says what it took back beside it; a quarter's statement settles the net.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE reinsurance.bordereau ADD COLUMN IF NOT EXISTS premium_reversed NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE reinsurance.bordereau ADD COLUMN IF NOT EXISTS commission_reversed NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE reinsurance.bordereau DROP CONSTRAINT IF EXISTS bordereau_reversed_not_negative;
ALTER TABLE reinsurance.bordereau
    ADD CONSTRAINT bordereau_reversed_not_negative CHECK (premium_reversed >= 0 AND commission_reversed >= 0);

ALTER TABLE reinsurance.bordereau_line DROP CONSTRAINT IF EXISTS bordereau_line_line_type_check;
ALTER TABLE reinsurance.bordereau_line
    ADD CONSTRAINT bordereau_line_line_type_check
    CHECK (line_type IN ('PREMIUM', 'XOL_PREMIUM', 'RECOVERY', 'FREE_LOOK_REVERSAL'));

-- One reversal line per policy on a bordereau; that a charge is taken back only once per treaty is the query's rule.
CREATE UNIQUE INDEX IF NOT EXISTS ux_bordereau_line_reversed_once
    ON reinsurance.bordereau_line (tenant_id, policy_number, bordereau_id) WHERE line_type = 'FREE_LOOK_REVERSAL';
