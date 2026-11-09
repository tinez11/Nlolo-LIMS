-- db-migrations/iam/V4__organisation_manage.sql
-- organisation.manage (2026-11-09, organisation settings, decision O2: administrators only): the company profile,
-- branding and document layout, signatories and branches. New tenants get it from BuiltInRoles; this gives it to the
-- built-in administrator role every existing tenant already holds. Only this one permission is added.
INSERT INTO iam.role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.role_id, 'organisation.manage'
  FROM iam.role r
 WHERE r.built_in AND r.code = 'ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
