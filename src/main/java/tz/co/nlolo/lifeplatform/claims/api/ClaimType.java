package tz.co.nlolo.lifeplatform.claims.api;

/**
 * Must match {@code db-migrations/claims/V1__create_claims_schema.sql:11}'s CHECK constraint
 * exactly.
 *
 * <p>{@code docs/08-implementation-roadmap.md:161} describes the hierarchy as
 * "death/maturity/surrender/disability" -- that line is wrong. The DDL CHECK constraint above,
 * the OpenAPI enum ({@code api/openapi/openapi-claims.yaml}), the AsyncAPI enum, and
 * {@code docs/03-aggregate-design.md:133} all agree on {@code CRITICAL_ILLNESS}, and there is no
 * {@code SURRENDER} claim type anywhere else in the spec set. Four sources beat one; this enum
 * intentionally does NOT add a SURRENDER type.
 */
public enum ClaimType {
    DEATH, DISABILITY, CRITICAL_ILLNESS, MATURITY
}
