package tz.co.nlolo.lifeplatform.distribution.api;

/** Matches {@code distribution.commission_plan.status}'s CHECK constraint exactly
 * (db-migrations/distribution/V1:34): {@code ('ACTIVE','RETIRED')}, a fixed two-value set
 * structurally identical to {@link LicenseStatus} and {@link StatementStatus}.
 *
 * <p>Task 3 deliberately left this column mapped as a plain {@code String} on
 * {@code CommissionPlan}, noting that "no {@code PlanStatus} type was requested for this task."
 * Task 5 is the first task that actually reads and writes plan status --
 * {@code createCommissionPlan} sets it, {@code getApplicablePlan} filters by it -- so the
 * deferral is resolved HERE rather than passed through silently a second time. Converted to an
 * enum for consistency with the established pattern, not left as a String: a fixed CHECK-backed
 * set is exactly what {@link LicenseStatus}/{@link StatementStatus} exist to model, and every
 * other call site (commission_rule.tier_type, agent_profile.license_status,
 * commission_statement.status) already follows it. */
public enum PlanStatus {
    ACTIVE,
    RETIRED
}
