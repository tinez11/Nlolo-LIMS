-- db-migrations/iam/V2__collections_approve.sql
-- collections.approve (2026-10-27): confirming a receipt someone else recorded -- cash, a bank deposit, a cheque, a
-- Lipa-number or TANQR payment. The user's decision: finance records and finance approves, two people. New tenants
-- get it from BuiltInRoles; this gives it to the built-in finance officer and administrator roles every existing
-- tenant already holds. A role an administrator has changed keeps every change; only this one permission is added.
INSERT INTO iam.role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.role_id, 'collections.approve'
  FROM iam.role r
 WHERE r.built_in AND r.code IN ('FINANCE_OFFICER', 'ADMIN')
ON CONFLICT (role_id, permission) DO NOTHING;
