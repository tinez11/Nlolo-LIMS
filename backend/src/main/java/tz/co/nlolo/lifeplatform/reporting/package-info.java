/**
 * Operational and management reports (spec 2026-10-11-reporting-management-design.md). Learns about
 * the platform from domain events and the audit journal only; reads no other module's tables.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "audit::api", "party::api", "product::api", "distribution::api" })
package tz.co.nlolo.lifeplatform.reporting;
