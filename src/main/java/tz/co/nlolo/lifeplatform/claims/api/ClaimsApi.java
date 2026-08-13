package tz.co.nlolo.lifeplatform.claims.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    record RegisterClaimRequest(String policyNumber, UUID claimantPartyId, ClaimType claimType,
                                 LocalDate dateOfEvent, ClaimDetails details) {}

    ClaimView registerClaim(RegisterClaimRequest request, String idempotencyKey, String registeredBy);

    ClaimView getClaim(UUID claimId);

    Page<ClaimView> searchClaims(ClaimStatus status, UUID claimantPartyId, Pageable pageable);

    ClaimAssessmentView submitAssessment(UUID claimId, String findings, BigDecimal recommendedAmount,
                                          String recommendedCurrency, boolean fraudIndicator, String assessedBy);

    void decideSettlement(UUID claimId, boolean approved, BigDecimal approvedAmount, String approvedCurrency,
                           String rejectionReason, String payeeRef, String idempotencyKey, String decidedBy);

    void reopenClaim(UUID claimId, String reason, String reopenedBy);

    ClaimEvidenceView attachEvidence(UUID claimId, String documentRef, String description, String uploadedBy);

    List<ClaimEvidenceView> listEvidence(UUID claimId);
}
