/**
 * Operational and management reports (spec 2026-10-11-reporting-management-design.md), and accounting reports
 * (plan 2026-10-14-reporting-accounting-batch-2a.md).
 *
 * <p>Two different relationships with the truth, deliberately. MANAGEMENT reports read a projection built from
 * domain events and the audit journal -- rebuildable, and the record of nothing, which is why deleting a
 * tenant's rows and replaying is always safe. ACCOUNTING reports read the general ledger LIVE through
 * {@code finaccounting::api}: a projected copy of the ledger would be a second set of books that can disagree
 * with the first, which is exactly what a trial balance exists to rule out.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "audit::api", "party::api", "product::api", "distribution::api", "finaccounting::api" })
package tz.co.nlolo.lifeplatform.reporting;
