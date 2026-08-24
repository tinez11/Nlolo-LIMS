package tz.co.nlolo.lifeplatform.party.infrastructure;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

/**
 * Bean Validation constraints mirror api/openapi/openapi-party.yaml's RegisterIndividualRequest.contactInfo
 * schema: phoneNumber is pattern-constrained (Tanzanian E.164, docs/04-api-contracts.md §5) but not
 * present in that schema's own "required" list, and email is explicitly nullable -- so both constraints
 * below are conditional (Jakarta's built-in constraints, @Pattern/@Email included, treat null as valid;
 * only @NotNull/@NotBlank reject it), matching the spec rather than over-constraining it.
 */
public record ContactInfo(
    @Pattern(regexp = "^\\+255\\d{9}$", message = "Phone number must match the Tanzanian E.164 pattern +255XXXXXXXXX")
    String phoneNumber,
    @Email
    String email) {}
