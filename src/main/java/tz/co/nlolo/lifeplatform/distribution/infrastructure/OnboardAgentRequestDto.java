package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/** {@code hierarchyParentId} is genuinely optional -- a top-of-hierarchy agent has none. */
public record OnboardAgentRequestDto(
    @NotNull UUID partyId,
    @NotBlank String licenseNumber,
    @NotNull LocalDate licenseExpiryDate,
    UUID hierarchyParentId) {}
