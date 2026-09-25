package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * @param ratePercent the percentage of premium, as typed: "12.5" is 12.5%. A string, like every
 *     other amount on the wire, so no double ever touches it.
 */
public record SetCommissionRateRequestDto(
    @NotNull UUID productId,
    @NotNull @Pattern(regexp = "^\\d{1,3}(\\.\\d{1,2})?$") String ratePercent) {}
