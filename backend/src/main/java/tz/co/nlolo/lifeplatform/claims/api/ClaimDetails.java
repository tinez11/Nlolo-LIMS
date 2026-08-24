package tz.co.nlolo.lifeplatform.claims.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed per docs/03-aggregate-design.md:133, which requires "exhaustive, compiler-checked
 * handling wherever claim-type-specific logic branches, instead of an open class hierarchy or a
 * stringly-typed discriminator with runtime-only safety."
 *
 * <p>FIELD SETS ARE A JUDGMENT CALL. docs/04-api-contracts.md:84 and openapi-claims.yaml:126 both
 * deferred the concrete per-type schemas to "Deliverable 6", which then only specified
 * {@code details JSONB NOT NULL}. These fields are a reasonable first cut pending real
 * claim-form requirements; they are stored as JSONB so adding a field later needs no migration.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "claimType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DeathClaimDetails.class, name = "DEATH"),
    @JsonSubTypes.Type(value = DisabilityClaimDetails.class, name = "DISABILITY"),
    @JsonSubTypes.Type(value = CriticalIllnessClaimDetails.class, name = "CRITICAL_ILLNESS"),
    @JsonSubTypes.Type(value = MaturityClaimDetails.class, name = "MATURITY")
})
public sealed interface ClaimDetails
        permits DeathClaimDetails, DisabilityClaimDetails, CriticalIllnessClaimDetails, MaturityClaimDetails {

    /** The claim type this details payload is valid for -- checked against the claim's own type at
     * registration so a DEATH claim can never carry DisabilityClaimDetails. */
    ClaimType claimType();
}
