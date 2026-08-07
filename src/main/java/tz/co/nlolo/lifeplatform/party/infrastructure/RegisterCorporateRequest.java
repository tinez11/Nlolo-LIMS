package tz.co.nlolo.lifeplatform.party.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// Mirrors api/openapi/openapi-party.yaml's RegisterCorporateRequest: required
// [registeredName, registrationNumber, contactInfo].
public record RegisterCorporateRequest(
    @NotBlank String registeredName,
    @NotBlank String registrationNumber,
    @Valid @NotNull ContactInfo contactInfo) {}
