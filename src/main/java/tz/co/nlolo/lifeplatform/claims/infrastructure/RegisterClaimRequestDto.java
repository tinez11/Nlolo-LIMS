package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.UUID;

/** {@code details} binds directly to the sealed {@link ClaimDetails} interface -- Jackson
 * resolves the concrete subtype via {@code ClaimDetails}'s own {@code @JsonTypeInfo(property =
 * "claimType")}/{@code @JsonSubTypes}, so the JSON {@code details} object must carry its own
 * {@code claimType} field alongside its type-specific fields (see {@code ClaimDetailsJsonbSmokeTest}
 * for a worked example of each variant's shape); this is the same polymorphic-by-claimType wiring
 * openapi-claims.yaml's {@code RegisterClaimRequest.details} description already promises. */
public record RegisterClaimRequestDto(
    @NotBlank @Pattern(regexp = "^[A-Z0-9-]{6,20}$") String policyNumber,
    @NotNull UUID claimantPartyId,
    @NotNull ClaimType claimType,
    @NotNull LocalDate dateOfEvent,
    @NotNull ClaimDetails details) {}
