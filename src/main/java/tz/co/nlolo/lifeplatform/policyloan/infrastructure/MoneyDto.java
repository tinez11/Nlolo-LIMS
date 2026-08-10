package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Mirrors policy.infrastructure.MoneyDto exactly (Task 4 precedent) -- the wire-shape
 * translation layer between openapi-common.yaml's Money schema and this module's flattened
 * LoanView (see LoanResponseDto). */
public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
