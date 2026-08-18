package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * The QUOTA_SHARE/percent pairing is NOT a bean-validation constraint here: {@code
 * ReinsuranceApiImpl} already enforces it as a 422, and duplicating it as a 400 would give the
 * same malformed request two different status codes depending on which layer noticed first.
 */
public record CreateTreatyRequestDto(
    @NotBlank String reinsurerName,
    @NotNull TreatyType treatyType,
    @Valid @NotNull MoneyDto retentionLimit,
    @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String cessionPercent,
    @NotNull LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
