-- db-migrations/iam/V3__reports_solvency_factors.sql
-- reports.solvency-factors (2026-11-09, the reports redesign batch 3): publishing a new version of the risk charges,
-- thresholds and stress shocks the solvency reports are worked out with. New tenants get it from BuiltInRoles (the
-- administrator holds every permission not reserved to an approver or a specialist); this gives it to the built-in
-- administrator role every existing tenant already holds. A role an administrator has changed keeps every change;
-- only this one permission is added. Anyone else is given it on the Roles page.
INSERT INTO iam.role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.role_id, 'reports.solvency-factors'
  FROM iam.role r
 WHERE r.built_in AND r.code = 'ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
