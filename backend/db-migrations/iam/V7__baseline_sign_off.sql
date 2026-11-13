-- ledger.baseline-sign-off (2026-11-12, finance findings 99 and 66): finance signs off the accounting-policy baseline by
-- name. New tenants get it from BuiltInRoles; this gives it to the built-in finance officer and finance approver roles
-- every existing tenant already holds. ifrs17.actuary-sign-off belongs to the new built-in Actuary role, which
-- AccessService seeds on the tenant's next request, as it did Management. Administrators hold neither.
INSERT INTO iam.role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.role_id, 'ledger.baseline-sign-off'
  FROM iam.role r
 WHERE r.built_in AND r.code IN ('FINANCE_OFFICER', 'FINANCE_APPROVER')
ON CONFLICT (role_id, permission) DO NOTHING;
