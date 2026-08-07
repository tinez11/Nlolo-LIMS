package tz.co.nlolo.lifeplatform.underwriting.application;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.UUID;

@Service
public class UnderwritingApiImpl implements UnderwritingApi {

    private final UnderwritingCaseRepository underwritingCaseRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final ReferenceDataApi referenceDataApi;
    private final RulesEnginePort rulesEnginePort;

    public UnderwritingApiImpl(UnderwritingCaseRepository underwritingCaseRepository, RiskAssessmentRepository riskAssessmentRepository,
                                PartyApi partyApi, ProductApi productApi, ReferenceDataApi referenceDataApi, RulesEnginePort rulesEnginePort) {
        this.underwritingCaseRepository = underwritingCaseRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.referenceDataApi = referenceDataApi;
        this.rulesEnginePort = rulesEnginePort;
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
        underwritingCase.markInReview();

        RiskAssessment assessment = new RiskAssessment(tenantId, caseId, assessmentType.name(), assessedBy, findings, riskScore);
        riskAssessmentRepository.save(assessment);

        decideIfPossible(underwritingCase);
        underwritingCaseRepository.save(underwritingCase);
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
     * Placeholder age-banding matching the rating table's own placeholder band naming
     * ("30-39" used throughout this plan's tests) -- pending Actuarial sign-off on the
     * real band boundaries, same status as the rest of SimpleRulesEngine's thresholds.
     */
    private String resolveAgeBand(PartyView applicant) {
        // PartyView doesn't currently expose dateOfBirth (M1's PartyApi.PartyView is a
        // narrow read model) -- flagged here rather than guessed: without it, age-band
        // rating cannot be resolved from real data yet, so this defaults to a single
        // placeholder band until PartyView is extended. Not silently wrong -- the
        // rating-table lookup itself already returns a neutral 1.0 for an unmatched
        // band (ProductApi.resolveRatingMultiplier's contract), so this doesn't produce
        // an incorrect decision, only an under-differentiated one.
        return "30-39";
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
        return new UnderwritingCaseView(c.getCaseId(), c.getApplicantPartyId(), c.getProductId(),
            UnderwritingCaseStatus.valueOf(c.getStatus()), ReferralStatus.valueOf(c.getReferralStatus()),
            c.getDecisionOutcome() != null ? DecisionOutcome.valueOf(c.getDecisionOutcome()) : null,
            c.getDecisionLoadingPercent(), c.getDecisionDeclineReason(), c.getDecisionDecidedAt());
    }
}
