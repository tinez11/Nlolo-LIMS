package tz.co.nlolo.lifeplatform.claims.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimAssessmentView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimEvidenceView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimNotFoundException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 4 implements {@link #registerClaim} and {@link #getClaim} only. The remaining
 * {@link ClaimsApi} methods are declared (the module's published surface was fixed by this
 * plan's authoring so {@code omnichannel} has a stable target) but not yet bodied here --
 * {@code submitAssessment} is Task 5's, {@code decideSettlement}/{@code reopenClaim} are Task
 * 9's, {@code attachEvidence}/{@code listEvidence}/{@code searchClaims} are Task 10's. Each
 * throws {@link UnsupportedOperationException} rather than faking a result, so a caller finds
 * out immediately rather than silently getting a wrong answer.
 */
@Service
public class ClaimsApiImpl implements ClaimsApi {

    private static final Logger log = LoggerFactory.getLogger(ClaimsApiImpl.class);

    private final ClaimRepository claimRepository;
    private final PolicyApi policyApi;
    private final PartyApi partyApi;
    private final UnderwritingApi underwritingApi;
    private final ApplicationEventPublisher eventPublisher;

    public ClaimsApiImpl(ClaimRepository claimRepository, PolicyApi policyApi, PartyApi partyApi,
                          UnderwritingApi underwritingApi, ApplicationEventPublisher eventPublisher) {
        this.claimRepository = claimRepository;
        this.policyApi = policyApi;
        this.partyApi = partyApi;
        this.underwritingApi = underwritingApi;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public ClaimView registerClaim(RegisterClaimRequest request, String idempotencyKey, String registeredBy) {
        UUID tenantId = TenantContext.get();

        // 1. Claimant must exist. PartyNotFoundException propagates as-is (404 at the boundary).
        partyApi.getParty(request.claimantPartyId());

        // 2. Policy must exist and have been in force on the date of the event -- not "now". A
        //    death claim filed after the policy lapsed is still valid if the death preceded the
        //    lapse. PolicyNotFoundException propagates as-is.
        PolicyView policy = policyApi.getPolicy(request.policyNumber());
        if (!policyApi.isPolicyInForce(request.policyNumber(), request.dateOfEvent())) {
            throw new ClaimValidationException("Policy " + request.policyNumber()
                + " was not in force on " + request.dateOfEvent());
        }

        // 3. Coverage consistency (openapi-claims.yaml's 422) -- reduced to a sum-assured check,
        //    for TWO independent reasons, both of which must stay in this comment:
        //
        //    (a) MODULE BOUNDARY. PolicyApi.getCoverageStatus returns CoverageStatusView, whose
        //        ActiveCoverageView carries a product.api.BenefitType. claims' allowedDependencies
        //        are { policy::api, underwriting::api, party::api, document::api, refdata::api } --
        //        product::api is NOT among them (docs/02-module-architecture.md:172), so any
        //        reference to BenefitType in claims' own bytecode is a claims->product dependency
        //        that ModularityTests rejects. Do NOT call getCoverageStatus, and do NOT add
        //        product::api to the allowed list to work around it.
        //    (b) NO DATA TO CHECK ANYWAY. PolicyApiImpl.java:95-99 only ever creates a DEATH
        //        coverage row, because ProductApi exposes no benefit-schedule getter -- so a
        //        per-benefit check could only ever fail for DISABILITY/CRITICAL_ILLNESS, never
        //        pass, and for DEATH it is tautological (every issued policy has exactly one
        //        DEATH row equal to its sum assured).
        //
        //    What remains genuinely meaningful is that the policy carries a positive sum assured
        //    to claim against. The real per-benefit check is deferred pending ProductApi work.
        if (policy.sumAssuredAmount() == null || policy.sumAssuredAmount().signum() <= 0) {
            throw new ClaimValidationException("Policy " + request.policyNumber()
                + " has no positive sum assured to claim against");
        }

        // 4. Contestability. Fails CLOSED on an unknown answer, or on any failure reaching
        //    underwriting -- see requiresContestabilityReview's own javadoc.
        boolean requiresContestabilityReview = requiresContestabilityReview(policy, request.dateOfEvent());

        // 5. The details payload must match the declared claim type. Claim's own constructor
        //    (Claim.java:107-113) already checks details.claimType() == claimType and throws
        //    ClaimValidationException on mismatch -- not duplicated here, that would be dead code.
        Claim claim = new Claim(tenantId, request.policyNumber(), request.claimantPartyId(),
            request.claimType(), request.dateOfEvent(), request.details(), registeredBy);
        claimRepository.save(claim);

        // Payload matches api/asyncapi-events.yaml's ClaimRegisteredPayload field-for-field
        // (claimId, policyNumber, claimantPartyId, claimType, dateOfEvent), plus one field
        // ClaimRegisteredPayload does not declare: requiresContestabilityReview. The payload is
        // an untyped Map (no schema is enforced at runtime) and the schema does not forbid
        // additional properties, so adding it here is the only way -- short of a migration out
        // of Task 1's scope -- for the assessor-facing consumer of this event to see the outcome
        // without re-deriving it itself.
        eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimRegistered", tenantId,
            Map.of("claimId", claim.getClaimId(),
                   "policyNumber", claim.getPolicyNumber(),
                   "claimantPartyId", claim.getClaimantPartyId(),
                   "claimType", claim.getClaimType().name(),
                   "dateOfEvent", claim.getDateOfEvent().toString(),
                   "requiresContestabilityReview", requiresContestabilityReview)));

        return toView(claim, requiresContestabilityReview);
    }

    @Override
    public ClaimView getClaim(UUID claimId) {
        Claim claim = findOrThrow(claimId, TenantContext.get());
        return toView(claim, deriveContestabilityReview(claim));
    }

    @Override
    public Page<ClaimView> searchClaims(ClaimStatus status, UUID claimantPartyId, Pageable pageable) {
        throw new UnsupportedOperationException("searchClaims is implemented in a later task of this plan");
    }

    @Override
    public ClaimAssessmentView submitAssessment(UUID claimId, String findings, BigDecimal recommendedAmount,
                                                 String recommendedCurrency, boolean fraudIndicator, String assessedBy) {
        throw new UnsupportedOperationException("submitAssessment is implemented in a later task of this plan");
    }

    @Override
    public void decideSettlement(UUID claimId, boolean approved, BigDecimal approvedAmount, String approvedCurrency,
                                  String rejectionReason, String payeeRef, String idempotencyKey, String decidedBy) {
        throw new UnsupportedOperationException("decideSettlement is implemented in a later task of this plan");
    }

    @Override
    public void reopenClaim(UUID claimId, String reason, String reopenedBy) {
        throw new UnsupportedOperationException("reopenClaim is implemented in a later task of this plan");
    }

    @Override
    public ClaimEvidenceView attachEvidence(UUID claimId, String documentRef, String description, String uploadedBy) {
        throw new UnsupportedOperationException("attachEvidence is implemented in a later task of this plan");
    }

    @Override
    public List<ClaimEvidenceView> listEvidence(UUID claimId) {
        throw new UnsupportedOperationException("listEvidence is implemented in a later task of this plan");
    }

    /**
     * True when the claim falls inside the contestability window and therefore needs a
     * non-disclosure review before approval.
     *
     * <p>Fails CLOSED in three distinct ways, all deliberate:
     * <ul>
     *   <li>A policy issued before M6 has a NULL underwritingCaseId (policy/V4 added the column
     *       with no backfill -- there is no source of truth for an already-issued policy's
     *       originating case). We cannot prove the claim is OUTSIDE the window, so we treat it as
     *       inside it: flagged for review rather than waved through.</li>
     *   <li>Any exception reaching underwriting (e.g. the case somehow no longer resolving) is
     *       treated the same way, for the same reason -- caught here rather than left to
     *       propagate, since an unhandled exception would abort registration entirely rather than
     *       degrade to "flag for review", which is what fail-closed is supposed to mean.</li>
     * </ul>
     * Being wrong in this direction costs a manual review; being wrong the other way approves a
     * potentially non-disclosed claim automatically.
     */
    private boolean requiresContestabilityReview(PolicyView policy, LocalDate dateOfEvent) {
        if (policy.underwritingCaseId() == null) {
            log.warn("Policy {} has no underwriting case id (issued before M6); treating claim as "
                + "within the contestability window and flagging for review", policy.policyNumber());
            return true;
        }
        try {
            // UnderwritingApiImpl.checkContestability (UnderwritingApiImpl.java:164-172) returns
            // true when the as-of date IS within the contestability window (needs review) and
            // false when it is safely outside -- confirmed by reading that method directly, no
            // inversion needed here.
            return underwritingApi.checkContestability(policy.underwritingCaseId(), dateOfEvent);
        } catch (RuntimeException e) {
            log.warn("Contestability check failed for policy {} (case {}); flagging for review",
                policy.policyNumber(), policy.underwritingCaseId(), e);
            return true;
        }
    }

    /** Re-derives {@code requiresContestabilityReview} for an already-registered claim, by
     * looking up its policy again -- see {@code ClaimView}'s javadoc for why there is no column. */
    private boolean deriveContestabilityReview(Claim claim) {
        try {
            PolicyView policy = policyApi.getPolicy(claim.getPolicyNumber());
            return requiresContestabilityReview(policy, claim.getDateOfEvent());
        } catch (RuntimeException e) {
            log.warn("Could not re-derive contestability review for claim {}; flagging for review",
                claim.getClaimId(), e);
            return true;
        }
    }

    private Claim findOrThrow(UUID claimId, UUID tenantId) {
        return claimRepository.findByClaimIdAndTenantId(claimId, tenantId)
            .orElseThrow(() -> new ClaimNotFoundException("Claim " + claimId + " not found"));
    }

    private ClaimView toView(Claim claim, boolean requiresContestabilityReview) {
        return new ClaimView(claim.getClaimId(), claim.getPolicyNumber(), claim.getClaimantPartyId(),
            claim.getClaimType(), claim.getStatus(), claim.getDateOfEvent(), claim.getDetails(),
            claim.getApprovedAmount(), claim.getApprovedCurrency(), requiresContestabilityReview);
    }
}
