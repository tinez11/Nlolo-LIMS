package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
 * @param joinedOn when cover starts for this person. Omit on an opening schedule to mean
 *     the scheme's commencement date. Backdating is normal -- a schedule reaches the
 *     insurer weeks after somebody started -- but a future date is refused.
 */
public record GroupMemberInputDto(
    @NotNull UUID memberPartyId,
    @Size(max = 30) String gradeCode,
    @DecimalMin("0.01") BigDecimal salaryAmount,
    LocalDate joinedOn) {

    public PolicyApi.MemberInput toApiInput() {
        return new PolicyApi.MemberInput(memberPartyId, gradeCode, salaryAmount, joinedOn);
    }
}
