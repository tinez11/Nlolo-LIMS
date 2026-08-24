package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Wire-shape translation between openapi-common.yaml's shared {@code Money} schema and this
 * module's views -- each module keeps its own copy rather than sharing one, since a shared DTO in
 * the root package would be a dependency every money-carrying module would have to take on.
 *
 * <p>Deliberately WITHOUT {@code @DecimalMin("0.01")}: {@code gl_posting.amount} is always a
 * positive magnitude by CHECK (see {@code PostingDirection}'s javadoc), and the constraint would
 * add nothing the DB does not already enforce. Copied verbatim from
 * {@code reinsurance.infrastructure.MoneyDto} to avoid a needless divergence in shape.
 */
public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
