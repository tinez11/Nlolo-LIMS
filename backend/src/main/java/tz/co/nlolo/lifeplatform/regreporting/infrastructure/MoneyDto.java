package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Wire-shape translation between openapi-common.yaml's shared {@code Money} schema and this
 * module's views -- each module keeps its own copy rather than sharing one, since a shared DTO in
 * the root package would be a dependency every money-carrying module would have to take on.
 *
 * <p>The leading {@code -?} matters here specifically (unlike most modules' copies): a cumulative
 * STOCK metric such as {@code SUM_ASSURED_IN_FORCE} can legitimately go negative when a period's
 * terminations exceed its issuances -- a real business outcome, not corrupt data (see
 * {@code ReturnLine}'s javadoc and Task 4's {@code CumulativeMetricTest}). Copied verbatim from
 * {@code reinsurance.infrastructure.MoneyDto} to avoid a needless divergence in shape.
 */
public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
