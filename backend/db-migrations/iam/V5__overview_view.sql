-- db-migrations/iam/V5__overview_view.sql
-- overview.view (2026-11-12, analytics plan 5, D1): the management overview. New tenants get it from BuiltInRoles; this
-- gives it to the built-in administrator role every existing tenant already holds. The Management role itself is
-- seeded by AccessService on the tenant's next request. Only this one permission is added.
INSERT INTO iam.role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.role_id, 'overview.view'
  FROM iam.role r
 WHERE r.built_in AND r.code = 'ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
