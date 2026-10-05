-- db-migrations/refdata/V7__unit_linked_price_move_alert.sql
-- Product step 6 (U1): how far a proposed fund price may move from the last approved one before the proposer
-- must say why. A prompt, not a block -- markets do jump -- but a typo looks exactly like a jump, so a large
-- move is never entered without a reason the approver can read. Reference data, so the investment committee
-- can move it without a deployment.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('UL_PRICE_MOVE_ALERT_PERCENT', 'DEFAULT', 'Percent move from the last approved fund price that needs a reason', '10', 'TZ'); -- Agreed 2026-10-05 (spec §3)
