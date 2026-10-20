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
    // Any everyday form of a Tanzanian number (2026-10-20): 0712 345 678, 255712345678, +255712345678. Shape only
    // here -- the digits, spaces and separators a number is written with; PhoneNumbers converts it to +255XXXXXXXXX
    // before it is stored, and refuses anything that is not a Tanzanian number in words.
    @Pattern(regexp = "^[+0-9\\s\\-.()]{9,20}$",
        message = "Enter a Tanzanian phone number, e.g. 0712 345 678 or +255712345678")
    String phoneNumber,
    @Email
    String email) {}
