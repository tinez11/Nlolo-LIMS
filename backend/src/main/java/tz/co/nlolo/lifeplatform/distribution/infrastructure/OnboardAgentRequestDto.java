package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.SalesChannel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code hierarchyParentId} is genuinely optional -- a top-of-hierarchy agent has none. {@code salesChannel} defaults
 * to AGENT and {@code homeBranch} is a refdata BRANCH code (IFRS 17 I2); the console sends both.
 */
public record OnboardAgentRequestDto(
    @NotNull UUID partyId,
    @NotBlank String licenseNumber,
    @NotNull LocalDate licenseExpiryDate,
    UUID hierarchyParentId,
    SalesChannel salesChannel,
    String homeBranch) {}
