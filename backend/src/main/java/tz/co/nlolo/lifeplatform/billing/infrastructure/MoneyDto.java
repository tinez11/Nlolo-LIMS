package tz.co.nlolo.lifeplatform.billing.infrastructure;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") @DecimalMin(value = "0.01") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
