package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifePremiumBasis;
import tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis;
import tz.co.nlolo.lifeplatform.policy.api.InterestMethod;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.RepaymentFrequency;

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
    /**
     * The lives the scheme opens with.
     *
     * <p>No longer {@code @NotEmpty}, for the same reason {@code flatBenefitAmount} and
     * {@code salaryMultiple} are not {@code @NotNull}: whether a scheme may open empty depends on
     * its {@code benefitBasis}, Bean Validation cannot express that, and the service refuses with
     * a message that names the basis. An employer scheme still cannot be empty -- its schedule is
     * the contract. A credit-life scheme can: the agreement with the lender exists before any
     * borrower, and the book arrives by file.
     */
    List<@Valid GroupMemberInputDto> openingSchedule,

    /**
     * Required on every scheme but credit life, whose contract carries no premium of its own: each accepted file is
     * invoiced (2026-11-01). The service refuses it missing anywhere else.
     */
    @Valid MoneyDto premium,
    String premiumFrequency,
    LocalDate commencementDate,
    /** Null for the usual annually renewable scheme. */
    @Positive Integer policyTermMonths,
    String reasonForManualIssue,
    /**
     * Null for an ordinary offer: the scheme is issued PROPOSED and the employer accepts it
     * by paying the first premium. A basis that already carries cover (MIGRATION,
     * CONVERSION, REINSTATEMENT) puts it on risk at once, because the contract is in force
     * somewhere else already.
     *
     * <p>Not @NotNull, and that asymmetry with ManualIssueRequestDto is deliberate: on the
     * individual manual-issue path a basis is REQUIRED because every such issuance is an
     * exception that has to name itself. Here the ordinary case is an offer, and null says
     * exactly that.
     */
    IssuanceBasis issuanceBasis,

    /**
     * The terms only a credit-life scheme may carry, all optional since 2026-11-01: a loan is priced on the lender's
     * yearly agreement and a claim pays the lender's statement, so the console no longer asks for them. Left out,
     * the loans are recorded flat and monthly and the scheme states no rate of its own. Refused on any other basis.
     */
    InterestMethod interestMethod,
    RepaymentFrequency repaymentFrequency,
    @DecimalMin("0.0001") BigDecimal premiumRatePercent,
    /**
     * How that rate is charged. Required alongside it on a credit-life scheme, and refused on
     * any other basis -- {@code PolicyApiImpl} enforces both, and
     * {@code chk_group_scheme_premium_basis_iff_rate} is the backstop.
     */
    CreditLifePremiumBasis premiumBasis,
    /**
     * The group case this scheme is issued on. REQUIRED for an employer (GROUP_LIFE) scheme --
     * the same rule manual issue has for a single life: nothing goes on risk on this route
     * without an underwriting case behind it. It must be a group proposal by this employer on
     * this product version, and one that no scheme came from yet.
     *
     * <p>Not required for credit life, whose scheme is set up by an underwriter from terms agreed
     * with the lender; their name is recorded as its underwriter of record instead.
     */
    UUID underwritingCaseId) {

    public PolicyApi.IssueGroupSchemeRequest toApiRequest(UUID productId) {
        return new PolicyApi.IssueGroupSchemeRequest(
            policyholderPartyId, productId, productVersionId, agentOfRecordId,
            benefitBasis, decimal(flatBenefitAmount), salaryMultiple, decimal(fclAmount), currency,
            grades != null ? grades.stream().map(GroupSchemeGradeInputDto::toApiInput).toList() : null,
            openingSchedule.stream().map(GroupMemberInputDto::toApiInput).toList(),
            premium == null ? null : new BigDecimal(premium.amount()), premium == null ? null : premium.currencyCode(),
            premiumFrequency != null && !premiumFrequency.isBlank() ? premiumFrequency
                : benefitBasis == BenefitBasis.AMORTISING_LOAN ? "SINGLE" : "ANNUALLY",
            commencementDate, policyTermMonths, reasonForManualIssue, issuanceBasis,
            interestMethod, repaymentFrequency, premiumRatePercent, premiumBasis);
    }

    private static BigDecimal decimal(String amount) {
        return amount != null && !amount.isBlank() ? new BigDecimal(amount) : null;
    }
}
