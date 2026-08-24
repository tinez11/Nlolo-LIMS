package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;

import java.time.LocalDate;
import java.util.UUID;

public record ClaimResponseDto(UUID claimId, String policyNumber, UUID claimantPartyId, ClaimType claimType,
                                ClaimStatus status, LocalDate dateOfEvent, ClaimDetails details,
                                MoneyDto approvedAmount, boolean requiresContestabilityReview) {

    public static ClaimResponseDto from(ClaimView view) {
        // approvedAmount is null until APPROVED -- unlike PolicyResponseDto's sumAssured (always
        // present), this MoneyDto is genuinely absent, not a legitimately-zero value, so no
        // MoneyDto is constructed at all rather than one wrapping a null/zero amount.
        MoneyDto approvedAmount = view.approvedAmount() != null
            ? new MoneyDto(view.approvedAmount().toPlainString(), view.approvedCurrency())
            : null;
        return new ClaimResponseDto(view.claimId(), view.policyNumber(), view.claimantPartyId(), view.claimType(),
            view.status(), view.dateOfEvent(), view.details(), approvedAmount, view.requiresContestabilityReview());
    }
}
