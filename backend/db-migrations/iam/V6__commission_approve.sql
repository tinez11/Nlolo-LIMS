-- db-migrations/iam/V6__commission_approve.sql
-- commission.approve (2026-11-12, finance finding 190): approving a commission plan or an agent's rate. New tenants get
-- it from BuiltInRoles; this gives it to the built-in finance officer and administrator roles every existing tenant
-- already holds. commission.approve-own is given to nobody: an administrator ticks it in Roles and permissions for a
-- company with one person (the user, 2026-11-12).
INSERT INTO iam.role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.role_id, 'commission.approve'
  FROM iam.role r
 WHERE r.built_in AND r.code IN ('FINANCE_OFFICER', 'ADMIN')
ON CONFLICT (role_id, permission) DO NOTHING;
