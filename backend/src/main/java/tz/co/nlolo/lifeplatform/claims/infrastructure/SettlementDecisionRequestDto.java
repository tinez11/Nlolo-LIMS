package tz.co.nlolo.lifeplatform.claims.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** {@code approvedAmount} and {@code payeeRef} are only meaningful (and only required) when
 * {@code approved} is {@code true} -- a cross-field rule Bean Validation cannot express with a
 * simple annotation, so it is left to {@code ClaimsApiImpl.decideSettlement}'s own checks, which
 * already reject a blank {@code payeeRef}/idempotency key on approval with a 422. */
public record SettlementDecisionRequestDto(
    @NotNull Boolean approved,
    @Valid MoneyDto approvedAmount,
    String rejectionReason,
    String payeeRef,
    // How it is paid (2026-10-28): MOBILE_MONEY (payeeRef is the number, the default), EFT (the four bank fields)
    // or CHEQUE (payeeRef is who it is made out to). Ignored on a rejection; refused on credit life.
    String method,
    String payeeBank,
    String payeeBranch,
    String payeeAccountName,
    String payeeAccountNumber,
    // A policy-term reason for a decline (ClaimDeclineReason): an exclusion window, or a funeral plan's
    // waiting period. Refused on an approval, and refused when its window was not open on the date of event.
    tz.co.nlolo.lifeplatform.claims.api.ClaimDeclineReason declineReason) {}
