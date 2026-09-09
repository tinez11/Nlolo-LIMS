-- db-migrations/communication/V2__template_identity.sql
-- A template is identified by tenant, key, channel AND language -- not by key alone.
--
-- V1 made template_key the sole PRIMARY KEY while giving the table `channel` and `language`
-- columns, which is a contradiction: it declares that OFFER_MADE varies by channel and language,
-- then makes it impossible to store more than one OFFER_MADE row in the entire database. Not per
-- tenant either, on a platform where every other table is tenant-scoped.
--
-- Latent since Deliverable 3 purely because nothing has ever inserted a row. The first seed of
-- four templates over two channels and two languages -- sixteen rows, of which exactly one would
-- have been insertable -- is what surfaced it.
--
-- A surrogate key rather than a wider composite PK. The natural key is four columns, three of
-- them varchar, and notification_dispatch would otherwise have to carry all four to reference a
-- row. It does not need to: see the FK note below.
ALTER TABLE communication.notification_template DROP CONSTRAINT notification_template_pkey CASCADE;

ALTER TABLE communication.notification_template
    ADD COLUMN template_id UUID PRIMARY KEY DEFAULT gen_random_uuid();

-- The real identity, and what the send path looks a template up by.
CREATE UNIQUE INDEX ux_notification_template
    ON communication.notification_template (tenant_id, template_key, channel, language);

-- The FK from notification_dispatch is deliberately NOT recreated against template_id.
--
-- CASCADE above dropped it along with the primary key it referenced. Restoring it would mean
-- either widening dispatch by three columns to carry the composite natural key, or storing a
-- template_id -- and a dispatch that stored template_id would point at a row whose body text is
-- editable, so the reference would silently stop describing what was actually sent the moment
-- somebody corrected a typo.
--
-- template_key stays on dispatch as a plain column: it records WHICH message this was, which is
-- the question an outbox answers, and the channel is already its own column there. A dispatch is
-- a historical record of an act, not a live join onto configuration.
COMMENT ON COLUMN communication.notification_dispatch.template_key IS
    'Which message this was. Deliberately not a foreign key: a dispatch records what was sent at the time, and template bodies are editable.';

COMMENT ON COLUMN communication.notification_template.template_key IS
    'The message this template renders, e.g. OFFER_MADE. Unique per tenant/channel/language, not globally.';
