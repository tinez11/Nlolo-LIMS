package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Wire-shape translation between openapi-common.yaml's shared {@code Money} schema and this
 * module's views -- each module keeps its own copy rather than sharing one, since a shared DTO in
 * the root package would be a dependency every money-carrying module would have to take on.
 *
 * <p><b>Deliberately WITHOUT the {@code @DecimalMin("0.01")} every other module's MoneyDto
 * carries.</b> A commission statement's total may legitimately be zero or negative when clawbacks
 * meet or exceed the period's accruals, and an accrual row is itself negative when it is a
 * reversal -- which is exactly why {@code distribution/V2} deliberately puts no positivity CHECK
 * on {@code commission_statement.total_amount}, unlike every other money column on this platform.
 * Copying the constraint across from claims would make the API unable to render its own correct
 * data. The regex already permits a leading minus.
 */
public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
