package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Issue a master group policy with its scheme configuration and opening schedule.
 *
 * <p><b>There is no sum assured field.</b> A scheme's sum assured is the total of its
 * members' cover, derived from {@code openingSchedule} in one place. A caller able to
 * supply it is a caller able to supply one that disagrees with the schedule underneath it
 * -- the same reasoning that keeps {@code maturityDate} off {@link ManualIssueRequestDto}.
 *
 * <p>Which of {@code flatBenefitAmount} / {@code salaryMultiple} / {@code grades} is
 * required depends on {@code benefitBasis}, so none can be {@code @NotNull} here. The
 * service checks the combination and answers 409 naming what is missing, which says more
 * than a field-level "must not be null" could.
 *
 * <p>Money amounts are bare decimal strings against the scheme's single {@code currency}
 * -- see {@link GroupSchemeGradeInputDto}. {@code salaryMultiple} is the exception and is
 * a plain number, because it is a ratio rather than money: 3.5x is not an amount and has
 * no currency, the same reading that keeps {@code sharePercent} a number on
 * {@link BeneficiaryInputDto}.
 */
public record IssueGroupSchemeRequestDto(
    @NotNull UUID policyholderPartyId,
    @NotNull UUID productVersionId,
    // Present-but-nullable, exactly as on ManualIssueRequestDto: a scheme sold direct has
    // no broker, and that is not the same as a caller forgetting the field.
    UUID agentOfRecordId,

    @NotNull BenefitBasis benefitBasis,
    @Pattern(regexp = MoneyAmounts.POSITIVE_AMOUNT) String flatBenefitAmount,
    @DecimalMin("0.01") BigDecimal salaryMultiple,
    /**
     * The free cover limit. Omit entirely for a scheme that has none -- which is a real
     * design for small flat schemes, and is NOT the same as a limit of zero, which would
     * send every member to underwriting. Zero is therefore rejected by the pattern rather
     * than accepted and reinterpreted.
     */
    @Pattern(regexp = MoneyAmounts.POSITIVE_AMOUNT) String fclAmount,

    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
    List<@Valid GroupSchemeGradeInputDto> grades,
    @NotEmpty List<@Valid GroupMemberInputDto> openingSchedule,

    @NotNull @Valid MoneyDto premium,
    String premiumFrequency,
    LocalDate commencementDate,
    /** Null for the usual annually renewable scheme. */
    @Positive Integer policyTermMonths,
    String reasonForManualIssue) {

    public PolicyApi.IssueGroupSchemeRequest toApiRequest(UUID productId) {
        return new PolicyApi.IssueGroupSchemeRequest(
            policyholderPartyId, productId, productVersionId, agentOfRecordId,
            benefitBasis, decimal(flatBenefitAmount), salaryMultiple, decimal(fclAmount), currency,
            grades != null ? grades.stream().map(GroupSchemeGradeInputDto::toApiInput).toList() : null,
            openingSchedule.stream().map(GroupMemberInputDto::toApiInput).toList(),
            new BigDecimal(premium.amount()), premium.currencyCode(),
            premiumFrequency != null && !premiumFrequency.isBlank() ? premiumFrequency : "ANNUALLY",
            commencementDate, policyTermMonths, reasonForManualIssue);
    }

    private static BigDecimal decimal(String amount) {
        return amount != null && !amount.isBlank() ? new BigDecimal(amount) : null;
    }
}
