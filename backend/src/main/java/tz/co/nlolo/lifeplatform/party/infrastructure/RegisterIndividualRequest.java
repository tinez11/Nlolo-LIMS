package tz.co.nlolo.lifeplatform.party.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.party.api.IdType;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.party.api.SmokerStatus;

import java.time.LocalDate;

/**
 * The wire shape of {@code POST /parties/individuals}.
 *
 * <p>Only {@code fullName}, {@code dateOfBirth} and {@code contactInfo} are required,
 * matching openapi-party.yaml's own {@code required} list. Everything added by the V2
 * person record is optional on purpose: registration happens in front of a person who
 * may not have the answers to hand, and an agent registering a walk-in should not be
 * blocked on an employer's name. A half-known record beats none.
 *
 * <p>Constraints here mirror the schema's, and Jakarta's value constraints treat null as
 * valid ({@code @Pattern}, {@code @Size} included) -- only {@code @NotNull}/{@code
 * @NotBlank} reject it -- so declaring them on optional fields constrains the value
 * without making the field mandatory.
 *
 * <p>{@code idType} and {@code idNumber} are both-or-neither. That is NOT expressed here:
 * Bean Validation has no clean cross-field constraint without a custom validator, and the
 * rule is already enforced twice where it counts -- in {@code IdentityDocument}'s compact
 * constructor and by {@code party_identity_document_complete} in the schema. A violation
 * therefore surfaces as a 400 from the domain rather than a field error, which is the
 * right trade for a rule no caller should be hitting.
 */
public record RegisterIndividualRequest(
    @NotBlank String fullName,
    @NotNull LocalDate dateOfBirth,
    @Valid @NotNull ContactInfo contactInfo,

    Sex sex,
    SmokerStatus smokerStatus,

    IdType idType,
    @Size(max = 50) String idNumber,

    @Size(max = 120) String occupation,
    @Size(max = 30) String occupationClass,
    @Size(max = 255) String employerName,

    @Pattern(regexp = "^[A-Za-z]{2}$", message = "Nationality must be an ISO 3166-1 alpha-2 code, e.g. TZ")
    String nationality,

    @Valid AddressRequest address) {}
