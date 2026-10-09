package tz.co.nlolo.lifeplatform.claims.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimJourneyApi;
import tz.co.nlolo.lifeplatform.claims.api.ClaimNotFoundException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.domain.ClaimAssessment;
import tz.co.nlolo.lifeplatform.claims.domain.ClaimDocumentRequest;
import tz.co.nlolo.lifeplatform.claims.domain.SettlementDecision;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimAssessmentRepository;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimDocumentRequestRepository;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimEvidenceRepository;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.claims.infrastructure.SettlementDecisionRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** See {@link ClaimJourneyApi}. */
@Service
public class ClaimJourneyApiImpl implements ClaimJourneyApi {

    /** The statuses in which the latest decision stands; a reopened or re-assessed claim has none yet. */
    private static final Set<ClaimStatus> DECIDED = EnumSet.of(ClaimStatus.APPROVED, ClaimStatus.REJECTED,
        ClaimStatus.SETTLEMENT_REQUESTED, ClaimStatus.SETTLED);

    private final ClaimRepository claims;
    private final ClaimAssessmentRepository assessments;
    private final SettlementDecisionRepository decisions;
    private final ClaimEvidenceRepository evidence;
    private final ClaimDocumentRequestRepository requests;

    public ClaimJourneyApiImpl(ClaimRepository claims, ClaimAssessmentRepository assessments,
                               SettlementDecisionRepository decisions, ClaimEvidenceRepository evidence,
                               ClaimDocumentRequestRepository requests) {
        this.claims = claims;
        this.assessments = assessments;
        this.decisions = decisions;
        this.evidence = evidence;
        this.requests = requests;
    }

    @Override
    @Transactional(readOnly = true)
    public Journey journey(UUID claimId) {
        UUID tenantId = TenantContext.get();
        Claim claim = claim(claimId, tenantId);
        List<ClaimAssessment> assessed = assessments.findByClaimIdAndTenantIdOrderByCreatedAtDesc(claimId, tenantId);
        SettlementDecision latest = DECIDED.contains(claim.getStatus())
            ? decisions.findByClaimIdAndTenantIdOrderByDecidedAtDesc(claimId, tenantId).stream().findFirst().orElse(null)
            : null;
        return new Journey(claimId, claim.getCreatedAt(),
            assessed.isEmpty() ? null : assessed.get(assessed.size() - 1).getCreatedAt(),
            latest != null ? latest.getDecidedAt() : null,
            latest != null ? latest.isApproved() : null,
            latest != null && latest.isApproved() ? latest.getApprovedAmount() : null,
            latest != null && latest.isApproved() ? latest.getApprovedCurrency() : null,
            claim.getDeclineReason() != null ? claim.getDeclineReason().name() : null,
            claim.getStatus() == ClaimStatus.SETTLED ? claim.getUpdatedAt() : null);
    }

    @Override
    @Transactional
    public DocumentRequest requestDocument(UUID claimId, String document, String reason, String requestedBy,
                                           String requestedByName) {
        UUID tenantId = TenantContext.get();
        Claim claim = claim(claimId, tenantId);
        if (claim.getStatus() == ClaimStatus.SETTLED) {
            throw new InvalidClaimStateException("Claim " + claimId + " is SETTLED; reopen it before asking for documents");
        }
        if (document == null || document.isBlank()) {
            throw new ClaimValidationException("Name the document the claimant should send");
        }
        ClaimDocumentRequest request = new ClaimDocumentRequest(tenantId, claimId, document.trim(),
            reason == null || reason.isBlank() ? null : reason.trim(), requestedBy, requestedByName);
        return view(requests.save(request));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentRequest> documentRequests(UUID claimId) {
        UUID tenantId = TenantContext.get();
        claim(claimId, tenantId);
        return requests.findByTenantIdAndClaimIdOrderByRequestedAtAsc(tenantId, claimId).stream()
            .map(ClaimJourneyApiImpl::view).toList();
    }

    @Override
    @Transactional
    public DocumentRequest fulfil(UUID claimId, UUID requestId, UUID claimEvidenceId) {
        UUID tenantId = TenantContext.get();
        ClaimDocumentRequest request = request(claimId, requestId, tenantId);
        if (request.getStatus() != ClaimDocumentRequest.Status.OPEN) {
            throw new ClaimValidationException("That document request is " + request.getStatus().name().toLowerCase()
                + "; it no longer takes a document");
        }
        boolean onThisClaim = evidence.findById(claimEvidenceId).map(e -> e.getClaimId().equals(claimId)).orElse(false);
        if (!onThisClaim) {
            throw new ClaimValidationException("The document is not on claim " + claimId);
        }
        request.received(claimEvidenceId);
        return view(requests.save(request));
    }

    @Override
    @Transactional
    public DocumentRequest withdraw(UUID claimId, UUID requestId) {
        ClaimDocumentRequest request = request(claimId, requestId, TenantContext.get());
        if (request.getStatus() == ClaimDocumentRequest.Status.OPEN) {
            request.withdrawn();
            requests.save(request);
        }
        return view(request);
    }

    private Claim claim(UUID claimId, UUID tenantId) {
        return claims.findByClaimIdAndTenantId(claimId, tenantId).orElseThrow(() -> new ClaimNotFoundException("Claim not found: " + claimId));
    }

    private ClaimDocumentRequest request(UUID claimId, UUID requestId, UUID tenantId) {
        return requests.findByTenantIdAndRequestId(tenantId, requestId)
            .filter(r -> r.getClaimId().equals(claimId))
            .orElseThrow(() -> new ClaimValidationException("Claim " + claimId + " has no document request " + requestId));
    }

    private static DocumentRequest view(ClaimDocumentRequest r) {
        return new DocumentRequest(r.getRequestId(), r.getClaimId(), r.getDocument(), r.getReason(),
            r.getRequestedByName(), r.getRequestedAt(), r.getStatus().name(), r.getClaimEvidenceId(), r.getReceivedAt());
    }
}
