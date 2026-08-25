package tz.co.nlolo.lifeplatform.claims.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimAssessmentView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimEvidenceView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimNotFoundException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.domain.ClaimAssessment;
import tz.co.nlolo.lifeplatform.claims.domain.SettlementDecision;
import tz.co.nlolo.lifeplatform.claims.domain.ClaimEvidence;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimAssessmentRepository;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimEvidenceRepository;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.claims.infrastructure.SettlementDecisionRepository;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 4 implements {@link #registerClaim} and {@link #getClaim}; Task 5 adds
 * {@link #submitAssessment}, {@link #decideSettlement}, and {@link #reopenClaim}; Task 8 adds
 * {@link #attachEvidence} and {@link #listEvidence}; Task 9 (this task) adds
 * {@link #searchClaims} (the REST layer's {@code GET /claims} needs it) and closes the
 * registration-idempotency gap in {@link #registerClaim} (see that method's own comments).
 * {@link ClaimsApi}'s full published surface is now bodied.
 */
@Service
public class ClaimsApiImpl implements ClaimsApi {

    private static final Logger log = LoggerFactory.getLogger(ClaimsApiImpl.class);

    private final ClaimRepository claimRepository;
    private final ClaimAssessmentRepository claimAssessmentRepository;
    private final SettlementDecisionRepository settlementDecisionRepository;
    private final ClaimEvidenceRepository claimEvidenceRepository;
    private final PolicyApi policyApi;
    private final PartyApi partyApi;
    private final UnderwritingApi underwritingApi;
    private final DocumentApi documentApi;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ClaimsApiImpl(ClaimRepository claimRepository, ClaimAssessmentRepository claimAssessmentRepository,
                          SettlementDecisionRepository settlementDecisionRepository,
                          ClaimEvidenceRepository claimEvidenceRepository, PolicyApi policyApi,
                          PartyApi partyApi, UnderwritingApi underwritingApi, DocumentApi documentApi,
                          ApplicationEventPublisher eventPublisher, PlatformTransactionManager transactionManager) {
        this.claimRepository = claimRepository;
        this.claimAssessmentRepository = claimAssessmentRepository;
        this.settlementDecisionRepository = settlementDecisionRepository;
        this.claimEvidenceRepository = claimEvidenceRepository;
        this.policyApi = policyApi;
        this.partyApi = partyApi;
        this.underwritingApi = underwritingApi;
        this.documentApi = documentApi;
        this.eventPublisher = eventPublisher;
        // REQUIRES_NEW, mirroring PaymentEventListener/PartyApiImpl's own precedent for a
        // constraint-violation-on-insert race: isolating the attempted INSERT in registerClaim
        // (below) to its OWN physical transaction means a caught unique-constraint violation on
        // claims/V3's partial index rolls back ONLY that inner transaction. A plain save() inside
        // this method's ambient @Transactional would instead leave the single shared Postgres
        // transaction aborted after the violation -- any further statement on it (the fallback
        // re-query this method performs next) would fail with "current transaction is aborted",
        // not the clean "return the existing claim" this method is written to do.
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public ClaimView registerClaim(RegisterClaimRequest request, String idempotencyKey, String registeredBy) {
        UUID tenantId = TenantContext.get();

        // 0. Registration idempotency key required (Task 9 review fix). Defense in depth, same
        //    shape as BillingApiImpl.requestPaymentForInvoice's own check: ClaimController rejects
        //    a missing/blank Idempotency-Key header before ever reaching here, but this method is
        //    ClaimsApi's own published contract, reachable by any future non-HTTP caller too, and a
        //    blank key would otherwise silently disable claims/V3's dedup index for that call.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ClaimValidationException("A registration idempotency key is required to register a claim");
        }

        // 1. Claimant must exist. PartyNotFoundException propagates as-is (404 at the boundary).
        partyApi.getParty(request.claimantPartyId());

        // 2. Policy must exist, and must be in force. PolicyNotFoundException propagates as-is.
        //
        //    KNOWN GAP, stated accurately here because the comment that used to sit on these lines
        //    described the intended contract as if it were the implemented one (M6 final-review I3).
        //    What SHOULD hold is "in force ON THE DATE OF THE EVENT" -- a death claim filed after
        //    the policy lapsed is still valid if the death itself preceded the lapse. What ACTUALLY
        //    happens is a "currently ACTIVE-or-REINSTATED" status read: PolicyApiImpl
        //    .isPolicyInForce accepts asOf and, by its own comment there, never consults it. The
        //    dateOfEvent below is therefore passed for the contract's sake and ignored downstream.
        //
        //    CONSEQUENCE: a legitimate claim registered after the policy has lapsed -- including
        //    after billing's dunning lapses it automatically at level >= 5, which a deceased
        //    policyholder's unpaid premiums cause -- is rejected with a 422 here, the exact opposite
        //    of the intended rule. Staff must reinstate the policy before the claim can be filed.
        //
        //    NOT fixed in claims, deliberately: the fix is a date-bounded coverage query inside
        //    policy (was the policy in force at date D, using lapsedAt/issueDate and the coverage
        //    rows), which is policy's own scope and a behaviour change for every existing caller of
        //    isPolicyInForce. Tracked as an open policy follow-up. Do not "fix" it here by widening
        //    the check to accept lapsed policies unconditionally -- that would let a claim be filed
        //    for an event that genuinely happened after coverage ended.
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
            request.claimType(), request.dateOfEvent(), request.details(), registeredBy, idempotencyKey);

        // 6. Attempt the insert in its OWN transaction (see the constructor's comment on
        //    requiresNewTransactionTemplate for why). The event publish happens INSIDE that same
        //    inner transaction -- not after it -- so the insert and the ClaimRegistered publish
        //    commit together atomically; a downstream AFTER_COMMIT listener then fires against
        //    THIS transaction's commit, exactly like claims.application.PaymentEventListener's own
        //    "producer's write must be durable first" convention, rather than racing a separate
        //    outer commit that does nothing else.
        //
        //    A repeat of the SAME idempotency key hits claims/V3's partial unique index and this
        //    catches it specifically, re-queries by key, and returns the EXISTING claim's view --
        //    "same key = same intent = deduped", matching this platform's idempotency semantics
        //    everywhere else (a repeat with the same key returns the prior result, it does not
        //    error). No event is published on that path -- ClaimRegistered already fired once, for
        //    the original attempt. A DIFFERENT key for what might be the same real-world event is
        //    deliberately treated as a genuinely new registration attempt -- see the
        //    two-distinct-claims test for why that is correct, not a gap, mirroring billing's
        //    retry-with-a-new-key idempotency pattern.
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                claimRepository.saveAndFlush(claim);
                // Payload matches api/asyncapi-events.yaml's ClaimRegisteredPayload field-for-field
                // (claimId, policyNumber, claimantPartyId, claimType, dateOfEvent), plus one field
                // ClaimRegisteredPayload does not declare: requiresContestabilityReview. The
                // payload is an untyped Map (no schema is enforced at runtime) and the schema does
                // not forbid additional properties, so adding it here is the only way -- short of a
                // migration out of Task 1's scope -- for the assessor-facing consumer of this event
                // to see the outcome without re-deriving it itself.
                eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimRegistered", tenantId,
                    Map.of("claimId", claim.getClaimId(),
                           "policyNumber", claim.getPolicyNumber(),
                           "claimantPartyId", claim.getClaimantPartyId(),
                           "claimType", claim.getClaimType().name(),
                           "dateOfEvent", claim.getDateOfEvent().toString(),
                           "requiresContestabilityReview", requiresContestabilityReview)));
            });
        } catch (DataIntegrityViolationException e) {
            Claim existing = claimRepository.findByTenantIdAndRegistrationIdempotencyKey(tenantId, idempotencyKey)
                .orElseThrow(() -> e); // a genuinely different constraint violation -- do not mask it
            return toView(existing, deriveContestabilityReview(existing));
        }

        return toView(claim, requiresContestabilityReview);
    }

    @Override
    public ClaimView getClaim(UUID claimId) {
        Claim claim = findOrThrow(claimId, TenantContext.get());
        return toView(claim, deriveContestabilityReview(claim));
    }

    /** Mirrors {@code PolicyApiImpl.searchPolicies}'s exact four-way branch on which optional
     * filters are present. Task 10 review fix: passes {@code status} itself (the {@link ClaimStatus}
     * enum), NOT {@code status.name()} -- see {@code ClaimRepository}'s own javadoc for why a
     * {@code String} argument throws {@code QueryArgumentException} unconditionally against
     * Hibernate 6.5's Criteria-derived query for this {@code @Enumerated(STRING)} column, a defect
     * {@code ClaimsContractTest} caught (this method had never once been called with a non-null
     * {@code status} by any prior test). */
    @Override
    public Page<ClaimView> searchClaims(ClaimStatus status, UUID claimantPartyId, Set<String> policyNumbers, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<Claim> page;
        // Same "leave the pre-existing derived-query paths alone for the common case" reasoning as
        // PolicyApiImpl.searchPolicies -- only an agents-realm caller's non-null policyNumbers
        // routes through the new three-way query.
        if (policyNumbers != null) {
            page = claimRepository.search(tenantId, claimantPartyId, status, policyNumbers, pageable);
        } else if (claimantPartyId != null && status != null) {
            page = claimRepository.findByTenantIdAndClaimantPartyIdAndStatus(tenantId, claimantPartyId, status, pageable);
        } else if (claimantPartyId != null) {
            page = claimRepository.findByTenantIdAndClaimantPartyId(tenantId, claimantPartyId, pageable);
        } else if (status != null) {
            page = claimRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = claimRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(claim -> toView(claim, deriveContestabilityReview(claim)));
    }

    @Override
    @Transactional
    public ClaimAssessmentView submitAssessment(UUID claimId, String findings, BigDecimal recommendedAmount,
                                                 String recommendedCurrency, boolean fraudIndicator, String assessedBy) {
        UUID tenantId = TenantContext.get();
        Claim claim = findOrThrow(claimId, tenantId);

        // REGISTERED or REOPENED -> UNDER_ASSESSMENT; a no-op if already UNDER_ASSESSMENT
        // (second/third assessor on the same claim), throws from any other status.
        claim.beginAssessment();

        ClaimAssessment assessment = new ClaimAssessment(tenantId, claimId, assessedBy, findings,
            recommendedAmount, recommendedCurrency, fraudIndicator);
        claimAssessmentRepository.save(assessment);

        // fraudIndicator is a SCRUTINY SIGNAL ONLY and must never itself reject the claim
        // (docs/03-aggregate-design.md:131, Cl1; restated in openapi-claims.yaml:139). It is
        // surfaced on the event so a fraud-review consumer can act on it, but nothing in this
        // method -- or in decideSettlement below -- lets a true value block approval.
        eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimAssessed", tenantId,
            Map.of("claimId", claimId, "fraudIndicator", fraudIndicator)));

        return toAssessmentView(assessment);
    }

    @Override
    @Transactional
    public void decideSettlement(UUID claimId, boolean approved, BigDecimal approvedAmount, String approvedCurrency,
                                  String rejectionReason, String payeeRef, String idempotencyKey, String decidedBy) {
        UUID tenantId = TenantContext.get();
        Claim claim = findOrThrow(claimId, tenantId);

        // claims/V1:56-59 documents this invariant and explicitly asks that it not be mistaken
        // for a missed validation: APPROVED requires >=1 assessment, EXCEPT MATURITY which may
        // auto-progress REGISTERED -> APPROVED with none (docs/03-aggregate-design.md:134, Cl3).
        if (approved && claim.getClaimType() != ClaimType.MATURITY
                && claimAssessmentRepository.countByClaimIdAndTenantId(claimId, tenantId) == 0) {
            throw new InvalidClaimStateException(
                "Claim " + claimId + " requires at least one assessment before approval");
        }

        // CLAIMS_ASSESSOR vs CLAIMS_MANAGER are deliberately separate roles; the role gate lives
        // at the controller (@PreAuthorize). This additionally blocks the same *person* from
        // assessing and then deciding, which a role check alone cannot catch.
        if (claimAssessmentRepository.existsByClaimIdAndTenantIdAndAssessor(claimId, tenantId, decidedBy)) {
            throw new ClaimValidationException(
                "Separation of duties: " + decidedBy + " assessed this claim and cannot also decide it");
        }

        if (approved) {
            if (payeeRef == null || payeeRef.isBlank()) {
                throw new ClaimValidationException("A payee reference is required to approve a claim");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                // Global Constraints: a blank idempotency key would silently reach the payment
                // rail zero times rather than failing loudly here.
                throw new ClaimValidationException("A settlement idempotency key is required to approve a claim");
            }

            // M6 final-review fix (I2). Claim.approve() is idempotent -- it early-returns when the
            // claim is ALREADY APPROVED -- which is exactly what this plan's designed retry path
            // needs: a rail decline returns the claim to APPROVED, and staff retry with a NEW
            // idempotency key. But a retry carrying a DIFFERENT approvedAmount used to reach the
            // published event, and therefore payment's real disbursement, while the claim's own
            // approved_amount silently kept the ORIGINAL value. Reviewer's empirical result: the
            // claim recorded 2,000,000 while 9,999,999 was actually disbursed and the claim reached
            // SETTLED -- the authoritative record understating the real payout by ~8M.
            //
            // Rejected rather than re-applied, on purpose: silently changing an approved settlement
            // amount on a retry is precisely what a financial audit needs to be explicit about, and
            // there is a clean path for a genuine change (reopen the claim, re-assess, decide
            // afresh). InvalidClaimStateException -> 409 CLAIM_INVALID_STATE, not
            // ClaimValidationException -> 422: the request is not malformed, it conflicts with the
            // claim's current recorded state, which is what 409 means and what
            // openapi-claims.yaml already declares for this operation ("...or is not in a status
            // that can be decided"). A retry with the SAME amount still passes straight through, so
            // idempotent redelivery keeps working. compareTo, not equals, so 2000000 and 2000000.00
            // are the same amount rather than a spurious conflict.
            if (claim.getStatus() == ClaimStatus.APPROVED) {
                boolean amountDiffers = approvedAmount == null || claim.getApprovedAmount() == null
                    || claim.getApprovedAmount().compareTo(approvedAmount) != 0;
                boolean currencyDiffers = !java.util.Objects.equals(claim.getApprovedCurrency(), approvedCurrency);
                if (amountDiffers || currencyDiffers) {
                    throw new InvalidClaimStateException("Claim " + claimId + " is already APPROVED for "
                        + claim.getApprovedAmount() + " " + claim.getApprovedCurrency()
                        + " and cannot be re-decided for " + approvedAmount + " " + approvedCurrency
                        + "; reopen the claim to change the approved settlement amount");
                }
            }

            settlementDecisionRepository.save(new SettlementDecision(tenantId, claimId, decidedBy, true,
                approvedAmount, approvedCurrency, null, payeeRef));

            // UNDER_ASSESSMENT -> APPROVED, or REGISTERED -> APPROVED for MATURITY's
            // auto-approval; also validates approvedAmount is positive.
            claim.approve(approvedAmount, approvedCurrency);
            eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimApproved", tenantId,
                Map.of("claimId", claimId, "policyNumber", claim.getPolicyNumber(),
                       "approvedAmount", Map.of("amount", approvedAmount.toPlainString(),
                                                 "currencyCode", approvedCurrency))));

            // APPROVED -> SETTLEMENT_REQUESTED. Task 6 owns turning this event into an actual
            // payment request; this method's job ends at publishing it.
            claim.markSettlementRequested(idempotencyKey);
            eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimSettlementRequested", tenantId,
                Map.of("claimId", claimId, "payeeRef", payeeRef,
                       "amount", Map.of("amount", approvedAmount.toPlainString(), "currencyCode", approvedCurrency),
                       "idempotencyKey", idempotencyKey)));
        } else {
            settlementDecisionRepository.save(new SettlementDecision(tenantId, claimId, decidedBy, false,
                null, null, rejectionReason, null));

            claim.reject();
            eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimRejected", tenantId,
                Map.of("claimId", claimId, "reason", rejectionReason == null ? "" : rejectionReason)));
        }
    }

    /**
     * {@code claim.reopen()} plus an audit trail. Deliberately publishes no new domain event:
     * {@code api/asyncapi-events.yaml} declares no reopen channel, and inventing one is out of
     * scope for this task. The reopen is logged at INFO with the reason and relies on
     * {@code audit}'s generic event capture rather than a bespoke {@code claims.ClaimReopened}
     * channel that no consumer subscribes to.
     */
    @Override
    @Transactional
    public void reopenClaim(UUID claimId, String reason, String reopenedBy) {
        UUID tenantId = TenantContext.get();
        Claim claim = findOrThrow(claimId, tenantId);

        // REJECTED or SETTLED -> REOPENED; a no-op if already REOPENED, throws from any other
        // status. Deliberately does not clear the prior approvedAmount (Claim.reopen's own doc).
        claim.reopen();

        log.info("Claim {} reopened by {}: {}", claimId, reopenedBy, reason);
    }

    @Override
    @Transactional
    public ClaimEvidenceView attachEvidence(UUID claimId, String documentRef, String description, String uploadedBy) {
        UUID tenantId = TenantContext.get();
        Claim claim = findOrThrow(claimId, tenantId);

        // Evidence is only meaningful while the claim can still be assessed or reopened.
        if (claim.getStatus() == ClaimStatus.SETTLED) {
            throw new InvalidClaimStateException(
                "Claim " + claimId + " is SETTLED; reopen it before attaching evidence");
        }

        // Confirms the ref exists AND belongs to this tenant -- DocumentApiImpl.findOrThrow
        // reports a cross-tenant ref identically to "doesn't exist" (DocumentApiImpl.java:67-74),
        // so this call both validates the ref and closes a cross-tenant reference hole in one go.
        // DocumentNotFoundException propagates as-is, mapped to 404 by DocumentExceptionHandler.
        documentApi.getMetadata(documentRef);

        ClaimEvidence evidence = new ClaimEvidence(tenantId, claimId, documentRef, description, uploadedBy);
        claimEvidenceRepository.save(evidence);

        return toEvidenceView(evidence);
    }

    @Override
    public List<ClaimEvidenceView> listEvidence(UUID claimId) {
        UUID tenantId = TenantContext.get();
        // Confirms the claim exists and belongs to this tenant before listing -- otherwise an
        // unknown/cross-tenant claimId would silently return an empty list instead of 404ing.
        findOrThrow(claimId, tenantId);

        return claimEvidenceRepository.findByClaimIdAndTenantIdOrderByUploadedAtDesc(claimId, tenantId).stream()
            .map(this::toEvidenceView)
            .toList();
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

    private ClaimAssessmentView toAssessmentView(ClaimAssessment assessment) {
        return new ClaimAssessmentView(assessment.getClaimAssessmentId(), assessment.getClaimId(),
            assessment.getAssessor(), assessment.getFindings(), assessment.getRecommendedAmount(),
            assessment.getRecommendedCurrency(), assessment.isFraudIndicator(), assessment.getCreatedAt());
    }

    private ClaimEvidenceView toEvidenceView(ClaimEvidence evidence) {
        return new ClaimEvidenceView(evidence.getClaimEvidenceId(), evidence.getClaimId(), evidence.getDocumentRef(),
            evidence.getDescription(), evidence.getUploadedBy(), evidence.getUploadedAt());
    }
}
