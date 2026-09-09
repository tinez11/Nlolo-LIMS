-- db-migrations/communication/V6__grants_and_rls.sql
-- app_role can finally read and write this schema, and only its own tenant's rows.
--
-- communication/V1 created three tables and granted app_role nothing. Every other module's
-- second migration does both halves of this; communication's never existed, because nothing had
-- ever read or written the schema. It surfaced the moment the module went live in a real
-- deployment: the reminder drain started up and every pass failed with
-- "permission denied for schema communication" -- caught on a dev restart rather than by any
-- test, because the test containers connect as the owning superuser and RLS and grants are
-- invisible to them.
--
-- That is the same shape as the platform's other two grant/RLS omissions, both of which were
-- also found only by running as app_role for real.
GRANT USAGE ON SCHEMA communication TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA communication TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA communication
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- Tenant isolation, same predicate every other module uses. Without it app_role could read every
-- tenant's message history -- which here means the wording sent to named individuals, their
-- policy numbers, and which of them could not be reached.
ALTER TABLE communication.notification_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY notification_template_tenant_isolation ON communication.notification_template
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE communication.notification_dispatch ENABLE ROW LEVEL SECURITY;
CREATE POLICY notification_dispatch_tenant_isolation ON communication.notification_dispatch
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- processed_event is deliberately NOT tenant-scoped and gets no policy.
--
-- It has no tenant_id column and should not have one: it is keyed on the domain event id, which
-- is globally unique, and its whole job is answering "has this exact event already been acted
-- on". A tenant predicate would add nothing -- an event id belongs to exactly one tenant by
-- construction -- while making the dedup check depend on a context that is set correctly
-- everywhere today and would fail OPEN if it ever were not, sending a duplicate SMS. Failing
-- closed on a missing context is worth more here than a redundant filter.
COMMENT ON TABLE communication.processed_event IS
    'Event-id dedup. Deliberately not tenant-scoped: an event id is globally unique and belongs to one tenant by construction, and a context-dependent predicate here would fail open into a duplicate message.';
