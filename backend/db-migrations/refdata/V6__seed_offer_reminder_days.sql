-- db-migrations/refdata/V6__seed_offer_reminder_days.sql
-- How long before an offer closes the customer gets a reminder.
--
-- Seven days against TZ_OFFER_VALIDITY_DAYS' thirty: one reminder, a week out. Long enough that
-- somebody paid monthly still has a payday inside the window, and late enough that the message
-- lands while the deadline is real rather than three weeks of nothing away.
--
-- One reminder rather than several, and that is a business decision rather than a technical
-- limit: a second and third message make the platform noise, and a customer who has learned to
-- ignore its SMS is worse off than one who got a single message they read. Adding a second
-- window later is a row here plus a template, not a redesign.
--
-- Reference data for the same reason TZ_OFFER_VALIDITY_DAYS is: a policy committee moves it, so
-- it should not need a deployment. The sweep reads it on every run.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('TZ_OFFER_REMINDER_DAYS', 'DEFAULT', 'Days before an offer closes that the customer is reminded', '7', 'TZ'); -- User-approved 2026-09-09
