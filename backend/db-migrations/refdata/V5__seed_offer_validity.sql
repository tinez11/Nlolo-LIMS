-- db-migrations/refdata/V5__seed_offer_validity.sql
-- How long an offer stays open before it becomes NOT_TAKEN_UP.
--
-- Long enough for mobile money after payday and for an agent's field receipt to reconcile;
-- short enough that nobody goes on risk against medical evidence assessed a season ago, which
-- is the reason offers expire at all rather than sitting open indefinitely.
--
-- Reference data rather than a constant in the SQL, following TZ_CONTESTABILITY_MONTHS and the
-- dunning thresholds: it is a business parameter a policy committee moves, not a fact. The sweep
-- in _post-migration/configure-offer-expiry-sweep.sql reads it on every run, so changing this row
-- changes the window without a deployment.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('TZ_OFFER_VALIDITY_DAYS', 'DEFAULT', 'Days an unpaid offer stays open before it becomes NOT_TAKEN_UP', '30', 'TZ'); -- PLACEHOLDER, pending Underwriting sign-off
