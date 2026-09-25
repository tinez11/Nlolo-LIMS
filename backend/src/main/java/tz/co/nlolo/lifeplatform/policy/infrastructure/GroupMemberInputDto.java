package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.policy.api.MemberType;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One life, on an opening schedule or joining later.
 *
 * <p>{@code gradeCode} and {@code salaryAmount} are optional here and conditional in the
 * service: each is required on exactly one benefit basis and <b>rejected</b> on the
 * others. Bean Validation cannot express "required given the scheme this is being posted
 * to", and the service's answer names the basis, which is what somebody fixing a
 * spreadsheet needs.
 *
 * <p><b>{@code memberPartyId} lost its {@code @NotNull}, and that is the point of this file's
 * last revision rather than a relaxation.</b> It was unconditionally required, which silently
 * made a whole product unreachable: a credit-life member is a LOAN, carried FREEFORM with a name
 * and a date of birth and no party row at all, because a lender's monthly file of several hundred
 * borrowers cannot mint a party per row. Plans 1 to 5 built that domain completely —
 * {@code PolicyApi.MemberInput.borrower(...)} has existed since plan 1 — while this DTO still
 * spoke only the employer-scheme vocabulary, so every credit-life scheme that had ever existed
 * was created from Java tests and none could be created over HTTP. The conditional rule
 * (a PARTY member needs an id, a FREEFORM member needs a name) belongs in the service beside the
 * basis rules it sits with, for exactly the reason the paragraph above gives.
 *
 * @param memberType {@code PARTY} for a person the platform already knows, {@code FREEFORM} for a
 *     name on a lender's schedule. Absent means {@code PARTY}, which is what every caller before
 *     credit life meant and keeps their requests working unchanged.
 * @param salaryAmount a bare decimal string -- see {@link GroupSchemeGradeInputDto} for
 *     why money here is neither a JSON number nor a {@code MoneyDto}.
 * @param joinedOn when cover starts for this person. Omit on an opening schedule to mean
 *     the scheme's commencement date. Backdating is normal -- a schedule reaches the
 *     insurer weeks after somebody started -- but a future date is refused.
 * @param loanAccountNumber the lender's own reference, optional since policy/V16: neither real
 *     lender holds one, which is why the insurer mints the member reference instead.
 * @param loanTerms required on an AMORTISING_LOAN scheme and rejected on any other.
 */
public record GroupMemberInputDto(
    MemberType memberType,
    UUID memberPartyId,
    @Size(max = 200) String memberName,
    LocalDate memberDateOfBirth,
    @Size(max = 30) String gradeCode,
    @Pattern(regexp = MoneyAmounts.POSITIVE_AMOUNT) String salaryAmount,
    LocalDate joinedOn,
    @Size(max = 50) String loanAccountNumber,
    @Valid LoanTermsDto loanTerms) {

    public PolicyApi.MemberInput toApiInput() {
        return new PolicyApi.MemberInput(
            memberType != null ? memberType : MemberType.PARTY,
            memberPartyId,
            memberName,
            memberDateOfBirth,
            gradeCode,
            salaryAmount != null ? new BigDecimal(salaryAmount) : null,
            joinedOn,
            loanAccountNumber,
            loanTerms != null ? loanTerms.toApiInput() : null);
    }
}
