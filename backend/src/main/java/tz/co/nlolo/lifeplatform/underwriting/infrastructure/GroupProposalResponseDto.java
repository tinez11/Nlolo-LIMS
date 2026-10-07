package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.GroupBenefitBasis;
import tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The wire shape of a proposed scheme.
 *
 * <p><b>Money as decimal STRINGS, never bare JSON numbers.</b> Returning
 * {@link GroupProposal} directly sent {@code 1200000.00} as {@code 1200000.0} — Jackson
 * renders a {@code BigDecimal} as a JSON number and the trailing zero is gone, along with any
 * guarantee about precision once a JavaScript client parses it into a double.
 *
 * <p>This is not a style preference and not a new rule: build5 hit the identical defect on the
 * member and scheme DTOs ("the first cut sent BigDecimal money as bare JSON numbers"), and the
 * console's own {@code lib/money.ts} calls a JSON number for money "a correctness bug". Every
 * other money amount on this platform is a decimal string with a currency beside it, and
 * openapi-underwriting.yaml already declares these as strings — so returning the API record
 * raw was a live contract mismatch as well.
 *
 * <p>{@code salaryMultiple} stays a number, because a ratio is not money — the same line
 * build5 drew.
 */
public record GroupProposalResponseDto(GroupBenefitBasis benefitBasis, String flatBenefitAmount,
                                        BigDecimal salaryMultiple, String fclAmount, String currency,
                                        List<GradeLineDto> grades, List<MemberLineDto> openingSchedule,
                                        String premiumAmount, String premiumCurrency,
                                        String premiumFrequency, LocalDate commencementDate,
                                        Integer policyTermMonths, String planCode, List<GroupProposal.LifeLine> lives) {

    public record GradeLineDto(String gradeCode, String benefitAmount) {}

    public record MemberLineDto(UUID memberPartyId, String gradeCode, String salaryAmount) {}

    public static GroupProposalResponseDto from(GroupProposal proposal) {
        return new GroupProposalResponseDto(
            proposal.benefitBasis(), plain(proposal.flatBenefitAmount()), proposal.salaryMultiple(),
            plain(proposal.fclAmount()), proposal.currency(),
            proposal.grades().stream()
                .map(g -> new GradeLineDto(g.gradeCode(), plain(g.benefitAmount()))).toList(),
            proposal.openingSchedule().stream()
                .map(m -> new MemberLineDto(m.memberPartyId(), m.gradeCode(), plain(m.salaryAmount()))).toList(),
            plain(proposal.premiumAmount()), proposal.premiumCurrency(), proposal.premiumFrequency(),
            proposal.commencementDate(), proposal.policyTermMonths(), proposal.planCode(), proposal.lives());
    }

    /** toPlainString, so 1200000.00 stays 1200000.00 and never becomes 1.2E+6. */
    private static String plain(BigDecimal amount) {
        return amount != null ? amount.toPlainString() : null;
    }
}
