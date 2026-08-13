package tz.co.nlolo.lifeplatform.claims.infrastructure;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Wire-shape translation between openapi-common.yaml's shared {@code Money} schema and this
 * module's views -- mirrors billing/policy/policyloan/payment's own module-local {@code MoneyDto}
 * verbatim; each module keeps its own copy rather than sharing one, since a shared DTO type in the
 * root package would be a dependency every module needing money would have to take on. */
public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") @DecimalMin(value = "0.01") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
