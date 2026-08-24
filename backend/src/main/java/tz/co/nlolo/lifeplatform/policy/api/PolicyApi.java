package tz.co.nlolo.lifeplatform.policy.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface PolicyApi {

    record BeneficiaryInput(BeneficiaryType type, UUID partyId, String freeformDesignee, BigDecimal sharePercent, boolean revocable) {}

    record IssueRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                         BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                         BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                         UUID agentOfRecordId, List<BeneficiaryInput> beneficiaries, String reasonForManualIssue) {}

    record EndorsementInput(String endorsementType, LocalDate effectiveDate, Map<String, Object> changes) {}

    PolicyView issuePolicy(UUID underwritingCaseId, IssueRequest request, String issuedBy);
    PolicyView applyEndorsement(String policyNumber, EndorsementInput request, String appliedBy);
    void replaceBeneficiaries(String policyNumber, List<BeneficiaryInput> beneficiaries, String changedBy);
    SurrenderQuoteView quoteSurrenderValue(String policyNumber);
    PolicyView getPolicy(String policyNumber);
    Page<PolicyView> searchPolicies(UUID policyholderPartyId, PolicyStatus status, Pageable pageable);
    CoverageStatusView getCoverageStatus(String policyNumber, LocalDate asOf);
    boolean isPolicyInForce(String policyNumber, LocalDate asOf);

    UUID reserveLoanValue(String policyNumber, BigDecimal amount, String currency, Duration ttl);
    void confirmReservation(UUID reservationId);
    void releaseReservation(UUID reservationId);

    /** M5: releases encumbrance applied by a confirmed reservation whose downstream disbursement
     * subsequently failed. Deliberately NOT releaseReservation -- that method correctly refuses a
     * CONFIRMED reservation, since un-confirming is not what this is. This reverses the
     * encumbrance while leaving the reservation's own terminal CONFIRMED status as the historical
     * record of what happened. */
    void releaseEncumbrance(String policyNumber, BigDecimal amount, String currency);

    void suspendPolicy(String policyNumber, String reason, String suspendedBy);
    void resumeSuspendedPolicy(String policyNumber, String resumedBy);
    void lapsePolicy(String policyNumber, String lapsedBy);
    void reinstatePolicy(String policyNumber, String reinstatedBy);

    /** A MATURITY claim settled, or the policy reached term. Terminal; idempotent on repeat. */
    void markMatured(String policyNumber, String maturedBy);

    /** A DEATH/DISABILITY/CRITICAL_ILLNESS claim settled: coverage is discharged, no further
     * premium is due. Terminal; idempotent on repeat. */
    void terminateForSettledClaim(String policyNumber, UUID claimId, String terminatedBy);
}
