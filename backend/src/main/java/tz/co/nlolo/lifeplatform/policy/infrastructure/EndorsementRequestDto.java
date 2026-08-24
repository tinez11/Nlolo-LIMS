package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Map;

public record EndorsementRequestDto(@NotBlank String endorsementType, @NotNull LocalDate effectiveDate, @NotNull Map<String, Object> changes) {}
