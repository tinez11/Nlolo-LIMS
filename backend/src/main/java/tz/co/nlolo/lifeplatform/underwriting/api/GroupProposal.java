package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A group scheme as asked for, before anybody has agreed to write it.
 *
 * <p>Everything {@code PolicyApi.issueGroupScheme} needs, held on the case so an underwriter
 * can see the whole proposal — terms, grade table and schedule — before a contract exists.
 * Issuance copies it; nothing here stays authoritative once a scheme is issued, in exactly the
 * way {@code proposal_beneficiary} stops being authoritative once beneficiaries are on the
 * policy.
 *
 * @param premiumAmount the premium AGREED with the employer. A scheme is not priced by the
 *     individual formula — that prices one life from one age band, and the age it would read
 *     is the employer's, a company insuring nobody. Carried verbatim to issuance.
 * @param commencementDate when the employer wants cover to start. May be backdated; a signed
 *     schedule commonly reaches the insurer weeks after the fact.
 */
public record GroupProposal(GroupBenefitBasis benefitBasis, BigDecimal flatBenefitAmount,
                             BigDecimal salaryMultiple, BigDecimal fclAmount, String currency,
                             List<GradeLine> grades, List<MemberLine> openingSchedule,
                             BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                             LocalDate commencementDate, Integer policyTermMonths) {

    /** One band on a GRADED proposal: a staff category and what it is worth. */
    public record GradeLine(String gradeCode, BigDecimal benefitAmount) {}

    /**
     * One life on the opening schedule.
     *
     * <p>No {@code joinedOn}: every life on an OPENING schedule joins when the scheme
     * commences, which {@link GroupProposal#commencementDate()} already says. Members who join
     * later are admitted against the issued scheme, not against the proposal.
     */
    public record MemberLine(UUID memberPartyId, String gradeCode, BigDecimal salaryAmount) {}
}
