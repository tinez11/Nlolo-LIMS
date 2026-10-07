package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.GroupBenefitBasis;
import tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Propose a group scheme: an employer asking to cover a schedule of lives.
 *
 * <p><b>Its own request, and its own endpoint, rather than a branch inside
 * {@link OpenCaseRequest}.</b> That one requires a {@code sumAssured}, and a group case
 * deliberately has none — valuing a schedule needs the benefit calculator that lives in the
 * policy module, so the figure appears only when policy derives it at issuance. Making
 * {@code sumAssured} conditionally optional would weaken the check that matters on every
 * individual case to accommodate one that never carries it.
 *
 * <p>The same reasoning already split {@code /group-schemes} out of {@code /policies}: a
 * scheme read as an individual thing answers a true and useless question.
 */
public record OpenGroupCaseRequest(
    @NotNull UUID policyholderPartyId,
    @NotNull UUID productId,
    @NotNull UUID productVersionId,
    /** Who sold it, carried to issuance so commission accrues. Null for a direct sale. */
    UUID agentOfRecordId,

    @NotNull GroupBenefitBasis benefitBasis,
    /** Required on FLAT and rejected on the others; the service answers 409 naming the basis. */
    @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String flatBenefitAmount,
    @Positive BigDecimal salaryMultiple,
    /**
     * The benefit a member gets without medical evidence. Omit for no limit — which is a real
     * scheme design, and NOT the same as a limit of zero, which would send every life to
     * underwriting.
     */
    @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String fclAmount,
    @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,

    List<@Valid GradeLineDto> grades,
    /** An employer scheme's registered lives; the service requires at least one (not on a FUNERAL_PLAN proposal). */
    List<@Valid MemberLineDto> openingSchedule,

    /**
     * The premium agreed with the employer. Not computed: the individual formula prices one
     * life from one age band, and the age it would read is the employer's. Required on every basis
     * but FUNERAL_PLAN, whose premium is members x the plan's group rate (the service checks).
     */
    @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String premiumAmount,
    @Pattern(regexp = "^[A-Z]{3}$") String premiumCurrency,
    String premiumFrequency,

    LocalDate commencementDate,
    /** Null for the usual annually renewable scheme. */
    @Positive Integer policyTermMonths,

    /** IFRS 17 I2, optional refdata codes -- see {@link OpenCaseRequest#salesChannel}. */
    String salesChannel,
    String branchCode,

    /** Group funeral schemes (FUNERAL_PLAN basis): the plan, and the opening members with their families. */
    String planCode,
    List<@Valid LifeLineDto> lives) {

    /** One life on a group funeral schedule; a family's lives share {@code memberReference}. */
    public record LifeLineDto(String memberReference, FuneralRole role, String fullName, LocalDate dateOfBirth,
                              String sex, String idNumber, Boolean student, String beneficiaryName,
                              String beneficiaryRelationship, String beneficiaryPhone) {}

    public record GradeLineDto(@NotNull String gradeCode,
                                @NotNull @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String benefitAmount) {}

    /** No joinedOn: every life on an opening schedule joins when the scheme commences. */
    public record MemberLineDto(@NotNull UUID memberPartyId, String gradeCode,
                                 @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String salaryAmount) {}

    public GroupProposal toApiProposal() {
        return new GroupProposal(benefitBasis, decimal(flatBenefitAmount), salaryMultiple,
            decimal(fclAmount), currency,
            grades != null
                ? grades.stream().map(g -> new GroupProposal.GradeLine(g.gradeCode(), decimal(g.benefitAmount()))).toList()
                : List.of(),
            openingSchedule != null
                ? openingSchedule.stream()
                    .map(m -> new GroupProposal.MemberLine(m.memberPartyId(), m.gradeCode(), decimal(m.salaryAmount())))
                    .toList()
                : List.of(),
            decimal(premiumAmount), premiumCurrency, premiumFrequency,
            commencementDate, policyTermMonths, planCode,
            lives != null
                ? lives.stream().map(l -> new GroupProposal.LifeLine(l.memberReference(), l.role(), l.fullName(),
                    l.dateOfBirth(), l.sex(), l.idNumber(), Boolean.TRUE.equals(l.student()), l.beneficiaryName(),
                    l.beneficiaryRelationship(), l.beneficiaryPhone())).toList()
                : List.of());
    }

    private static BigDecimal decimal(String amount) {
        return amount != null && !amount.isBlank() ? new BigDecimal(amount) : null;
    }
}
