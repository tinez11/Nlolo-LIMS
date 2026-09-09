-- db-migrations/communication/V8__platform_default_templates.sql
-- Templates that work for a tenant nobody has seeded yet.
--
-- V3 seeded sixteen rows against one hardcoded tenant, the dev one. Point the platform at a
-- production tenant with any other uuid and the lookup misses on every send: each notification
-- records FAILED with "no template seeded", and the offer flow runs silently uncommunicative --
-- which is the exact failure the whole project exists to prevent. A tenant would have to be
-- provisioned by hand, in SQL, before a single customer could be told anything.
--
-- The fix is a PLATFORM DEFAULT set under the nil uuid, with per-tenant rows overriding it. A new
-- tenant works on day one; a tenant that wants its own wording writes its own row and that row
-- wins. Same shape as refdata, which is global with jurisdiction overrides, and the same
-- reasoning: message wording is configuration with a sensible default, not tenant data that must
-- be created before use.
--
-- The nil uuid is the sentinel because it cannot collide with a real tenant and reads
-- unmistakably as "not a tenant" to anyone looking at the table.
UPDATE communication.notification_template
   SET tenant_id = '00000000-0000-0000-0000-000000000000'
 WHERE tenant_id = '11111111-1111-1111-1111-111111111111';

COMMENT ON COLUMN communication.notification_template.tenant_id IS
    'The tenant whose wording this is. The nil uuid 00000000-0000-0000-0000-000000000000 means the platform default, used by any tenant that has not written its own row for that key/channel/language.';

-- ============================================================================================
-- RLS: every tenant may READ the defaults, and no tenant may WRITE them
-- ============================================================================================
-- Reading is the point: a default nobody can see is a default nobody can use.
--
-- Writing is the danger, and it needs saying out loud. Without a WITH CHECK, the USING clause
-- governs UPDATE too -- so one tenant editing a default would silently rewrite the wording every
-- OTHER tenant receives. That is a cross-tenant write dressed up as an edit, and it is the worst
-- thing this table could allow. WITH CHECK pins every written row to the caller's own tenant, so
-- the database refuses it even if the application forgets to.
--
-- The application does not forget: NotificationApiImpl.rewordTemplate copies a default into the
-- caller's tenant rather than updating it. This is the second lock on that door.
DROP POLICY notification_template_tenant_isolation ON communication.notification_template;
CREATE POLICY notification_template_tenant_isolation ON communication.notification_template
    USING (
        tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        OR tenant_id = '00000000-0000-0000-0000-000000000000'
    )
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
