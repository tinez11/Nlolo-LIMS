package tz.co.nlolo.lifeplatform.party.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// Mirrors api/openapi/openapi-party.yaml's RegisterIndividualRequest: required [fullName, dateOfBirth, contactInfo].
public record RegisterIndividualRequest(
    @NotBlank String fullName,
    @NotNull LocalDate dateOfBirth,
    @Valid @NotNull ContactInfo contactInfo) {}
