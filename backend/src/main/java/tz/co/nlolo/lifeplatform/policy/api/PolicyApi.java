package tz.co.nlolo.lifeplatform.policy.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Every policy that currently names {@code partyId} as a beneficiary.
     *
     * <p>The reverse of {@link #replaceBeneficiaries}'s direction, and previously unanswerable:
     * beneficiary rows were only ever read by policy number, so a person's exposure as a
     * beneficiary was stored and unreachable. Returns an empty list for a party named on nothing,
     * which is the common case and not an error.
     */
    List<BeneficiaryOfView> beneficiaryOf(UUID partyId);
    SurrenderQuoteView quoteSurrenderValue(String policyNumber);
    PolicyView getPolicy(String policyNumber);

    /**
     * {@code agentOfRecordIds} is null/empty for "no agent filter" (staff and customer callers);
     * a non-empty set restricts results to policies whose {@code agentOfRecordId} is one of the
     * given ids -- an agents-realm caller's own resolved hierarchy team (see
     * {@code DistributionApi.resolveAgentTeam}), computed by the controller, not this method.
     */
    Page<PolicyView> searchPolicies(UUID policyholderPartyId, PolicyStatus status, Set<UUID> agentOfRecordIds, String q, Pageable pageable);

    /**
     * The policy numbers an agents-realm caller's own hierarchy team (itself plus its downline,
     * within {@code DistributionApi.resolveAgentTeam}'s depth cap) is entitled to see -- resolves
     * {@code callerPartyId}'s team via {@code DistributionApi} internally, so {@code claims} (which
     * has no distribution dependency of its own, only {@code policy::api}) can scope its own
     * "browse my book" claims list/detail through this single call rather than needing the
     * distribution dependency itself. Empty if the party is not an agent in this tenant.
     */
    Set<String> policyNumbersForAgentTeam(UUID callerPartyId);

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
