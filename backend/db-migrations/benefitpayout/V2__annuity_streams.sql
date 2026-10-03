-- db-migrations/benefitpayout/V2__annuity_streams.sql
-- Product step 5 (D1): an annuity's income is a stream with NO end date, expanded a horizon at a
-- time by a drain, rather than to a maturity date that does not exist.
--
-- The base amount and escalation are the contract's locked figures; amount_multiplier is 1 until a
-- joint life's first death sets the survivor percentage. A death inside a guarantee REDIRECTS the
-- stream: from redirect_from to redirect_until the instalments go to the beneficiaries (to
-- redirect_payee_ref when one is known; otherwise each waits for a reviewer to enter one), and proof
-- of life stops, because the annuitant has died.
--
-- Every new stream column is nullable or defaulted, so every existing stream and test fixture is
-- untouched; PayoutStream maps them, which is why every class applying benefitpayout V1 takes V2.
ALTER TABLE benefitpayout.payout_instalment DROP CONSTRAINT payout_instalment_kind_check;
ALTER TABLE benefitpayout.payout_instalment ADD CONSTRAINT payout_instalment_kind_check
    CHECK (kind IN ('SURVIVAL','MATURITY','INCOME','RETURN_OF_PREMIUM','ANNUITY'));

ALTER TABLE benefitpayout.payout_stream
    ADD COLUMN open_ended             BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN frequency              VARCHAR(12),
    ADD COLUMN first_due_date         DATE,
    ADD COLUMN base_amount            NUMERIC(19,2),
    ADD COLUMN currency               CHAR(3),
    ADD COLUMN escalation_percent     NUMERIC(9,4),
    ADD COLUMN amount_multiplier      NUMERIC(9,4) NOT NULL DEFAULT 1,
    ADD COLUMN expanded_through       DATE,
    ADD COLUMN redirect_from          DATE,
    ADD COLUMN redirect_until         DATE,
    ADD COLUMN redirect_payee_ref     VARCHAR(200),
    ADD COLUMN proof_of_life_stopped  BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT payout_stream_open_ended_shape CHECK (NOT open_ended OR
        (frequency IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL') AND first_due_date IS NOT NULL
         AND base_amount > 0 AND currency IS NOT NULL AND escalation_percent IS NOT NULL
         AND expanded_through IS NOT NULL)),
    ADD CONSTRAINT payout_stream_multiplier_range CHECK (amount_multiplier > 0 AND amount_multiplier <= 1),
    ADD CONSTRAINT payout_stream_redirect_shape CHECK (redirect_until IS NULL OR redirect_from IS NOT NULL);

-- Proof of life never suspends a stream whose annuitant has died: its instalments now go to the
-- beneficiaries, who are not the person whose life was being proved.
CREATE OR REPLACE FUNCTION benefitpayout.streams_due_for_proof_of_life()
RETURNS TABLE (stream_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT s.stream_id, s.tenant_id FROM benefitpayout.payout_stream s
     WHERE s.status = 'ACTIVE' AND NOT s.proof_of_life_stopped AND s.proof_of_life_due_date < current_date LIMIT 500;
$$;

-- The roll-forward drain's selector: open-ended streams still running whose expansion stops short of
-- the horizon. Ids only, as every drain selector here returns.
CREATE OR REPLACE FUNCTION benefitpayout.annuity_streams_due_for_rollforward(horizon DATE)
RETURNS TABLE (stream_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT s.stream_id, s.tenant_id FROM benefitpayout.payout_stream s
     WHERE s.open_ended AND s.status IN ('PENDING_ACTIVATION','ACTIVE','SUSPENDED')
       AND s.expanded_through < horizon
       AND (s.redirect_until IS NULL OR s.expanded_through < s.redirect_until)
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION benefitpayout.annuity_streams_due_for_rollforward(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION benefitpayout.annuity_streams_due_for_rollforward(DATE) TO app_role;
