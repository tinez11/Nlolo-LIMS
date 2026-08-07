package tz.co.nlolo.lifeplatform.underwriting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.underwriting.api.*;
import tz.co.nlolo.lifeplatform.underwriting.domain.*;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.RiskAssessmentRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnderwritingCaseRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UnderwritingApiImpl implements UnderwritingApi {

    private final UnderwritingCaseRepository underwritingCaseRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final ReferenceDataApi referenceDataApi;
    private final RulesEnginePort rulesEnginePort;
    private final ApplicationEventPublisher eventPublisher;

    public UnderwritingApiImpl(UnderwritingCaseRepository underwritingCaseRepository, RiskAssessmentRepository riskAssessmentRepository,
                                PartyApi partyApi, ProductApi productApi, ReferenceDataApi referenceDataApi, RulesEnginePort rulesEnginePort,
                                ApplicationEventPublisher eventPublisher) {
        this.underwritingCaseRepository = underwritingCaseRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.referenceDataApi = referenceDataApi;
        this.rulesEnginePort = rulesEnginePort;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, String openedBy) {
        UUID tenantId = TenantContext.get();
        // Confirms the applicant party genuinely exists and belongs to this tenant --
        // PartyApi.getParty already throws PartyNotFoundException on cross-tenant access
        // (M1's anti-enumeration pattern), which is exactly the failure mode we want here too.
        partyApi.getParty(applicantPartyId);

        UnderwritingCase underwritingCase = new UnderwritingCase(tenantId, applicantPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency, openedBy);
        underwritingCaseRepository.save(underwritingCase);
        return toView(underwritingCase);
    }

    @Override
    @Transactional
    public UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy) {
        UUID tenantId = TenantContext.get();
        UnderwritingCase underwritingCase = findOrThrow(caseId, tenantId);
        // Guard against silently overwriting an already-decided case (final review
        // finding 3): without this, a second submitAssessment call recomputes and
        // overwrites decision_outcome/decision_decline_reason/decision_decided_at in
        // place with zero record of what the original decision was. A case that
        // genuinely needs re-assessment (e.g. new medical evidence after a DECLINE)
        // must go through an explicit re-open step, not yet modeled, rather than have
        // this method quietly recompute over an existing decision.
        if (UnderwritingCaseStatus.DECIDED.name().equals(underwritingCase.getStatus())) {
            throw new UnderwritingCaseAlreadyDecidedException(caseId);
        }
        underwritingCase.markInReview();

        RiskAssessment assessment = new RiskAssessment(tenantId, caseId, assessmentType.name(), assessedBy, findings, riskScore);
        riskAssessmentRepository.save(assessment);

        decideIfPossible(underwritingCase);
        underwritingCaseRepository.save(underwritingCase);

        // M3 addition: the ONLY producer of underwriting.UnderwritingDecisionMade anywhere in
        // the codebase -- policy.application.UnderwritingDecisionEventListener is this event's
        // sole consumer and has nothing to react to without this call (see plan Global
        // Constraints -- underwriting published zero domain events before this task).
        if (UnderwritingCaseStatus.DECIDED.name().equals(underwritingCase.getStatus())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("caseId", caseId);
            payload.put("outcome", underwritingCase.getDecisionOutcome());
            // loadingPercent is genuinely nullable (only set when outcome=LOADED per the
            // chk_loading_only_when_loaded DB CHECK) -- Map.of(...) would throw NPE here for
            // every other outcome, hence the mutable map (Global Constraints).
            payload.put("loadingPercent", underwritingCase.getDecisionLoadingPercent());
            payload.put("decidedAt", underwritingCase.getDecisionDecidedAt().toString());
            eventPublisher.publishEvent(DomainEventEnvelope.of("underwriting.UnderwritingDecisionMade", tenantId, payload));
        }
        return toView(underwritingCase);
    }

    private void decideIfPossible(UnderwritingCase underwritingCase) {
        PartyView applicant = partyApi.getParty(underwritingCase.getApplicantPartyId());
        String ageBand = resolveAgeBand(applicant);
        String sumAssuredBand = resolveSumAssuredBand(underwritingCase.getSumAssuredAmount());

        BigDecimal ageMultiplier = productApi.resolveRatingMultiplier(underwritingCase.getProductVersionId(), FactorType.AGE, ageBand);
        BigDecimal sumAssuredMultiplier = productApi.resolveRatingMultiplier(underwritingCase.getProductVersionId(), FactorType.SUM_ASSURED_BAND, sumAssuredBand);

        List<BigDecimal> riskScores = riskAssessmentRepository.findByCaseId(underwritingCase.getCaseId()).stream()
            .map(RiskAssessment::getRiskScore)
            .filter(java.util.Objects::nonNull)
            .toList();

        RiskProfile profile = new RiskProfile(ageMultiplier, sumAssuredMultiplier, riskScores);
        UnderwritingDecision decision = rulesEnginePort.evaluate(profile);

        underwritingCase.recordDecision(decision.outcome().name(), decision.loadingPercent(), decision.reason());
    }

    /**
     * Age-band rating is NOT YET RESOLVABLE: {@code PartyView} (M1's narrow read model)
     * exposes no {@code dateOfBirth} field, so there is no real data this method can band
     * on. Deliberately returns a sentinel that cannot collide with any real band a product
     * defines -- "UNKNOWN" is not a valid AGE band value any product's rating table would
     * ever declare (real bands look like "30-39", "40-49", etc.), so
     * {@code ProductApi.resolveRatingMultiplier}'s neutral-1.0-on-no-match fallback
     * genuinely applies here: every applicant, regardless of actual age, gets a neutral
     * AGE contribution rather than being silently rated against whichever real band
     * happens to share this placeholder's name. (A previous version of this method
     * returned the literal "30-39" -- itself a real band many products define with a
     * non-1.0 multiplier -- which meant every applicant, including a 22-year-old or a
     * 78-year-old, was actively rated as if they were 30-39. That was not a neutral
     * placeholder, it was silently wrong.) Revisit once PartyView exposes dateOfBirth (or
     * a narrow age-only accessor) so a real band can be computed.
     */
    private String resolveAgeBand(PartyView applicant) {
        return "UNKNOWN";
    }

    private String resolveSumAssuredBand(BigDecimal sumAssuredAmount) {
        if (sumAssuredAmount == null) return "LOW";
        if (sumAssuredAmount.compareTo(new BigDecimal("10000000")) >= 0) return "HIGH";
        if (sumAssuredAmount.compareTo(new BigDecimal("2000000")) >= 0) return "MEDIUM";
        return "LOW";
    }

    @Override
    public UnderwritingCaseView getCase(UUID caseId) {
        return toView(findOrThrow(caseId, TenantContext.get()));
    }

    @Override
    @Transactional
    public void referToSeniorUnderwriter(UUID caseId) {
        UnderwritingCase underwritingCase = findOrThrow(caseId, TenantContext.get());
        underwritingCase.referToSeniorUnderwriter();
        underwritingCaseRepository.save(underwritingCase);
    }

    @Override
    public boolean checkContestability(UUID caseId, LocalDate asOfDate) {
        UnderwritingCase underwritingCase = findOrThrow(caseId, TenantContext.get());
        if (underwritingCase.getDecisionDecidedAt() == null) {
            return true; // No decision yet -- treat as within the contestability window (nothing to contest).
        }
        int contestabilityMonths = Integer.parseInt(referenceDataApi.getValue("TZ_CONTESTABILITY_MONTHS", "TZ"));
        LocalDate decidedDate = underwritingCase.getDecisionDecidedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        return Period.between(decidedDate, effectiveAsOf).toTotalMonths() < contestabilityMonths;
    }

    private UnderwritingCase findOrThrow(UUID caseId, UUID tenantId) {
        return underwritingCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
            .orElseThrow(() -> new UnderwritingCaseNotFoundException(caseId));
    }

    private UnderwritingCaseView toView(UnderwritingCase c) {
        return new UnderwritingCaseView(c.getCaseId(), c.getApplicantPartyId(), c.getProductId(), c.getProductVersionId(),
            UnderwritingCaseStatus.valueOf(c.getStatus()), ReferralStatus.valueOf(c.getReferralStatus()),
            c.getDecisionOutcome() != null ? DecisionOutcome.valueOf(c.getDecisionOutcome()) : null,
            c.getDecisionLoadingPercent(), c.getDecisionDeclineReason(), c.getDecisionDecidedAt(),
            c.getSumAssuredAmount(), c.getSumAssuredCurrency());
    }
}
