package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * The QUOTA_SHARE/percent pairing is NOT a bean-validation constraint here: {@code
 * ReinsuranceApiImpl} already enforces it as a 422, and duplicating it as a 400 would give the
 * same malformed request two different status codes depending on which layer noticed first.
 *
 * <p>{@code reinsurerName} is different (final review, M3): both blank AND over-length are bean-
 * validation constraints here, so both become 400 at the SAME layer rather than splitting across
 * two status codes. {@code ReinsuranceApiImpl.createTreaty} keeps its own length check as-is --
 * defense-in-depth for a future non-HTTP caller of the published API, the same convention {@code
 * ClaimController}'s Idempotency-Key check uses -- so the 200-character limit is enforced twice,
 * deliberately, at two layers that agree.
 */
public record CreateTreatyRequestDto(
    @NotBlank @Size(max = 200) String reinsurerName,
    @NotNull TreatyType treatyType,
    @Valid @NotNull MoneyDto retentionLimit,
    @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String cessionPercent,
    @NotNull LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
