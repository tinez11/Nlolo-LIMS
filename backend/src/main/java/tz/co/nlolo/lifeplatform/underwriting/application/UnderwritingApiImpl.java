package tz.co.nlolo.lifeplatform.underwriting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.underwriting.api.*;
import tz.co.nlolo.lifeplatform.underwriting.domain.*;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.RiskAssessmentRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnderwritingCaseRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, UUID agentOfRecordId, String openedBy) {
        UUID tenantId = TenantContext.get();
        // Confirms the applicant party genuinely exists and belongs to this tenant --
        // PartyApi.getParty already throws PartyNotFoundException on cross-tenant access
        // (M1's anti-enumeration pattern), which is exactly the failure mode we want here too.
        partyApi.getParty(applicantPartyId);

        // agentOfRecordId is stored as an opaque id and NOT validated against distribution:
        // this module declares no dependency on it, and the same convention already applies to
        // productId and applicantPartyId's outbound refs. PolicyApiImpl treats the field the
        // same way on the manual-issue path.
        UnderwritingCase underwritingCase = new UnderwritingCase(tenantId, applicantPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency, agentOfRecordId, openedBy);
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
        // genuinely needs re-assessment after a real decision (new medical evidence
        // following a DECLINE) must go through an explicit re-open step, not yet
        // modeled, rather than have this method quietly recompute over it.
        //
        // POSTPONED IS THE EXCEPTION, and always should have been. It is the one outcome
        // that means "not decided yet -- come back with more evidence", and treating it as
        // final made it the only outcome that could never be resolved: the engine returns
        // POSTPONED for a risk score >= 90 asking for further medical evidence, the case
        // locked, and no amount of further evidence could ever be submitted against it. A
        // postponed case is work in progress wearing a terminal status.
        //
        // Overwriting the decision columns is safe here specifically because the history
        // survives elsewhere: every assessment is its own RiskAssessment row, and the
        // superseded POSTPONED was published as UnderwritingDecisionMade and is durably
        // recorded in the audit journal. Nothing is lost that was not already written down.
        if (isDecided(underwritingCase) && !DecisionOutcome.POSTPONED.name().equals(underwritingCase.getDecisionOutcome())) {
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
        if (isDecided(underwritingCase)) {
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

    private static boolean isDecided(UnderwritingCase underwritingCase) {
        return UnderwritingCaseStatus.DECIDED.name().equals(underwritingCase.getStatus());
    }

    /**
     * The scores the engine weighs: the MOST RECENT assessment of each type, not every
     * assessment ever recorded.
     *
     * <p>This used to be every score on the case, and the engine takes the maximum, which
     * made a postponed case unresolvable in practice. The engine returns POSTPONED for a
     * score of 90 or more, asking for further medical evidence; when that evidence arrived
     * and was assessed at 30, the maximum was still the superseded 90, so the case
     * postponed again, forever. Allowing re-assessment without this would have been a fix
     * that changed nothing.
     *
     * <p>Latest PER TYPE rather than latest overall, because the three types answer
     * different questions. A fresh MEDICAL assessment supersedes the earlier medical view;
     * it says nothing about an OCCUPATIONAL red flag, and dropping that flag because a
     * later assessment of a different kind came in would quietly discard evidence. So each
     * dimension keeps its own current answer and the engine still takes the worst of them.
     *
     * <p>Ordered by {@code createdAt} then id: {@code createdAt} is {@code Instant.now()}
     * in Java, so two assessments recorded in the same request can share it exactly, and
     * "latest" would otherwise be decided by scan order — the failure this codebase has
     * now found five times.
     */
    private List<BigDecimal> latestScorePerAssessmentType(UUID caseId) {
        return riskAssessmentRepository.findByCaseId(caseId).stream()
            .filter(a -> a.getRiskScore() != null)
            .collect(java.util.stream.Collectors.groupingBy(RiskAssessment::getAssessmentType,
                java.util.stream.Collectors.collectingAndThen(
                    java.util.stream.Collectors.maxBy(
                        java.util.Comparator.comparing(RiskAssessment::getCreatedAt)
                            .thenComparing(RiskAssessment::getRiskAssessmentId)),
                    latest -> latest.map(RiskAssessment::getRiskScore).orElseThrow())))
            .values().stream()
            .toList();
    }

    private void decideIfPossible(UnderwritingCase underwritingCase) {
        String sumAssuredBand = resolveSumAssuredBand(underwritingCase.getSumAssuredAmount());

        BigDecimal ageMultiplier = resolveAgeMultiplier(underwritingCase);
        BigDecimal sumAssuredMultiplier = productApi.resolveRatingMultiplier(underwritingCase.getProductVersionId(), FactorType.SUM_ASSURED_BAND, sumAssuredBand);

        List<BigDecimal> riskScores = latestScorePerAssessmentType(underwritingCase.getCaseId());

        RiskProfile profile = new RiskProfile(ageMultiplier, sumAssuredMultiplier, riskScores);
        UnderwritingDecision decision = rulesEnginePort.evaluate(profile);

        underwritingCase.recordDecision(decision.outcome().name(), decision.loadingPercent(), decision.reason());
    }

    /**
     * The applicant's AGE multiplier, from their real date of birth.
     *
     * <p>This method used to return the sentinel band {@code "UNKNOWN"}, because
     * {@code PartyView} exposed no date of birth and there was nothing to band on. The
     * sentinel matched no product's rating table, so every applicant resolved to the
     * neutral 1.0 and AGE — the factor that dominates mortality — was never rated at all.
     * A 22-year-old and a 78-year-old were underwritten identically. {@code
     * PartyDetailView} exposes the date of birth now, so the block is gone.
     *
     * <p>Resolved by RANGE, not by rendering an age back into a band string:
     * {@code rating_table} carries real {@code age_from}/{@code age_to} bounds as of V5,
     * precisely so nothing has to guess how a publisher spelled a band.
     *
     * <p>Neutral 1.0 when the applicant has no recorded date of birth. A CORPORATE or GROUP
     * party genuinely has none, and an individual registered without one is a real record
     * on this platform — refusing to decide those cases would break underwriting for every
     * group scheme, so age simply does not contribute. It is a rating input, not an
     * identity check.
     *
     * <p>Age is taken as at TODAY rather than at the case's opening date. Cases are decided
     * within days of opening here, the difference can only matter to an applicant with a
     * birthday in that window, and an as-at-opening rule would need an opening date on the
     * aggregate that is not currently read anywhere. Worth revisiting if cases ever sit
     * open long enough for it to move a decision.
     */
    private BigDecimal resolveAgeMultiplier(UnderwritingCase underwritingCase) {
        PartyDetailView applicant = partyApi.getPartyDetail(underwritingCase.getApplicantPartyId());
        LocalDate dateOfBirth = applicant.dateOfBirth();
        if (dateOfBirth == null) {
            return BigDecimal.ONE;
        }
        int age = Period.between(dateOfBirth, LocalDate.now()).getYears();
        return productApi.resolveAgeMultiplier(underwritingCase.getProductVersionId(), age);
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
    public Page<UnderwritingCaseView> listCases(UnderwritingCaseStatus status, UUID applicantPartyId,
                                                 Set<UUID> applicantPartyIds, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<UnderwritingCase> page;
        // The two pre-existing derived-query paths stay untouched for the common staff case (no
        // applicant filter, no agent scope) -- only a caller that actually needs one of the new
        // dimensions routes through `search`. Same reasoning as ClaimsApiImpl.searchClaims.
        if (applicantPartyId != null || applicantPartyIds != null) {
            page = underwritingCaseRepository.search(tenantId, status != null ? status.name() : null,
                applicantPartyId, applicantPartyIds, pageable);
        } else if (status != null) {
            page = underwritingCaseRepository.findByTenantIdAndStatus(tenantId, status.name(), pageable);
        } else {
            page = underwritingCaseRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(this::toView);
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
            c.getSumAssuredAmount(), c.getSumAssuredCurrency(), c.getAgentOfRecordId());
    }
}
