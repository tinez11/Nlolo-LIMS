package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Wire-shape translation between openapi-common.yaml's shared {@code Money} schema and this
 * module's views.
 *
 * <p>The {@code @Pattern} is the shared {@code Money} regex verbatim, including its leading
 * {@code -?}, which is correct for a signed ledger context but wrong for every request field
 * that binds to this record. {@code @DecimalMin("0.01")} narrows it, matching
 * {@code policyloan.infrastructure.MoneyDto} -- the two records no longer diverge (M3 final
 * review, I3; that divergence was previously documented in policyloan's Javadoc as
 * deliberate, and the review's ruling was that it should not be).
 *
 * <p>The single request binding is {@code ManualIssueRequestDto.sumAssured}
 * ({@code POST /policies/manual-issue}). Without the floor, that endpoint accepted
 * {@code {"amount": "-5000000.00"}} and persisted a negative {@code policy.sum_assured_amount}
 * and a negative {@code policy.coverage} row. Unlike policyloan's case this does not feed the
 * encumbrance arithmetic ({@code PolicyApiImpl.issuePolicy} always creates the
 * {@code PolicyAccount} with {@code BigDecimal.ZERO} cash value), which is why it was rated
 * Important rather than Critical -- but a negative sum assured is a death-benefit figure that
 * {@code claims} and {@code finaccounting} consume in M4/M5, and "staff-only" is not a trust
 * boundary that should be carrying money integrity.
 *
 * <p>Response DTOs are never run through the validator, so {@code PolicyResponseDto} and
 * {@code CoverageStatusResponseDto} -- which reuse this record to render a legitimately-zero
 * cash value -- are unaffected.
 */
public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") @DecimalMin(value = "0.01") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
