/**
 * Role dashboards (2026-10-14): one typed view per working role, composed from the module APIs that own each figure.
 *
 * <p>Composition only. This module stores nothing and computes nothing a module already computes: a count is a
 * search's {@code totalElements} filtered by one status, and a money figure is a report's own number. Its one rule is
 * PRODUCT.md's -- a figure is a count or a report's own number -- and a figure it cannot read is null, never zero.
 *
 * <p>Nothing depends on this module, so it can depend on any {@code api} without risking a cycle. The customer
 * dashboard is NOT here: it predates this module and lives in {@code omnichannel}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "underwriting::api", "claims::api", "billing::api", "payment::api", "finaccounting::api",
    "reporting::api", "party::api", "policy::api", "distribution::api", "audit::api" })
package tz.co.nlolo.lifeplatform.dashboard;
