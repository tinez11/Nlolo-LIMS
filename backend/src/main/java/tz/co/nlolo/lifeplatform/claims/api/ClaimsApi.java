package tz.co.nlolo.lifeplatform.claims.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code claims} module's whole published surface -- {@code omnichannel} depends on
 * {@code claims::api} and this interface is everything it (and any other consumer) can see.
 *
 * <p>Only {@link #registerClaim} and {@link #getClaim} have implementations as of this task;
 * the remaining methods are declared here because the module's full surface was fixed by this
 * plan's authoring, but their bodies are Task 5 ({@code submitAssessment}), Task 9
 * ({@code decideSettlement}, {@code reopenClaim}), and Task 10 ({@code attachEvidence},
 * {@code listEvidence}, {@code searchClaims}) -- see {@code ClaimsApiImpl} for the exact split.
 */
public interface ClaimsApi {

    /**
     * @param policyMemberId the insured life this claim is for. REQUIRED when the policy is a
     *     group scheme — a scheme insures many lives, and "somebody on GL-000123 died" cannot
     *     be assessed, valued or paid — and REJECTED on individual business, where the policy
     *     names the life itself. Both rules are enforced through
     *     {@code PolicyApi.claimableCover}, because {@code claims} cannot see a product
     *     category without breaking its own allowed-dependency list.
     *     <p>Distinct from {@code claimantPartyId}, which is who is FILING: the widow, not the
     *     deceased. Conflating the two is what left a group claim unable to say who died.
     */
    record RegisterClaimRequest(String policyNumber, UUID policyMemberId, UUID claimantPartyId,
                                 ClaimType claimType, LocalDate dateOfEvent, ClaimDetails details) {}

    ClaimView registerClaim(RegisterClaimRequest request, String idempotencyKey, String registeredBy);

    ClaimView getClaim(UUID claimId);

    /**
     * {@code policyNumbers} is null for "no agent filter" (staff and customer callers); a
     * non-null (possibly empty) set restricts results to claims filed against one of the given
     * policy numbers -- an agents-realm caller's own book of business, resolved by the controller
     * via {@code PolicyApi.policyNumbersForAgentTeam} (claims has no agentOfRecordId of its own,
     * only the policy it references, so this joins through policy rather than needing a
     * distribution dependency here).
     */
    Page<ClaimView> searchClaims(ClaimStatus status, UUID claimantPartyId, Set<String> policyNumbers, String q, Pageable pageable);

    ClaimAssessmentView submitAssessment(UUID claimId, String findings, BigDecimal recommendedAmount,
                                          String recommendedCurrency, boolean fraudIndicator, String assessedBy);

    void decideSettlement(UUID claimId, boolean approved, BigDecimal approvedAmount, String approvedCurrency,
                           String rejectionReason, String payeeRef, String idempotencyKey, String decidedBy);

    void reopenClaim(UUID claimId, String reason, String reopenedBy);

    ClaimEvidenceView attachEvidence(UUID claimId, String documentRef, String description, String uploadedBy);

    List<ClaimEvidenceView> listEvidence(UUID claimId);
}
