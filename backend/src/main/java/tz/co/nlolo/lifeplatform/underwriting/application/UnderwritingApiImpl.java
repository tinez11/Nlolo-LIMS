package tz.co.nlolo.lifeplatform.underwriting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.underwriting.api.*;
import tz.co.nlolo.lifeplatform.underwriting.domain.*;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.MedicalDisclosureRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.ProposalBeneficiaryRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.RiskAssessmentRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnderwritingCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final MedicalDisclosureRepository medicalDisclosureRepository;
    private final ProposalBeneficiaryRepository proposalBeneficiaryRepository;
    /** Serialises the disclosure Q&A set into its JSONB column -- see recordDisclosures. */
    private final ObjectMapper objectMapper;

    public UnderwritingApiImpl(UnderwritingCaseRepository underwritingCaseRepository, RiskAssessmentRepository riskAssessmentRepository,
                                PartyApi partyApi, ProductApi productApi, ReferenceDataApi referenceDataApi, RulesEnginePort rulesEnginePort,
                                ApplicationEventPublisher eventPublisher,
                                MedicalDisclosureRepository medicalDisclosureRepository, ObjectMapper objectMapper,
                                ProposalBeneficiaryRepository proposalBeneficiaryRepository) {
        this.proposalBeneficiaryRepository = proposalBeneficiaryRepository;
        this.underwritingCaseRepository = underwritingCaseRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.referenceDataApi = referenceDataApi;
        this.rulesEnginePort = rulesEnginePort;
        this.eventPublisher = eventPublisher;
        this.medicalDisclosureRepository = medicalDisclosureRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * The self-insured overload.
     *
     * <p>{@code @Transactional} lives here, not on a {@code default} interface method, so
     * the delegation below happens inside an already-open transaction. See
     * {@link UnderwritingApi#openCase(UUID, UUID, UUID, BigDecimal, String, UUID, String)}.
     */
    @Override
    @Transactional
    public UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, UUID agentOfRecordId, String openedBy) {
        return openCase(applicantPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency,
            agentOfRecordId, ProposalDetails.selfInsured(), openedBy);
    }

    @Override
    @Transactional
    public UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, UUID agentOfRecordId, ProposalDetails proposal, String openedBy) {
        UUID tenantId = TenantContext.get();
        // Confirms the applicant party genuinely exists and belongs to this tenant --
        // PartyApi.getParty already throws PartyNotFoundException on cross-tenant access
        // (M1's anti-enumeration pattern), which is exactly the failure mode we want here too.
        partyApi.getParty(applicantPartyId);

        // agentOfRecordId is stored as an opaque id and NOT validated against distribution:
        // this module declares no dependency on it, and the same convention already applies to
        // productId and applicantPartyId's outbound refs. PolicyApiImpl treats the field the
        // same way on the manual-issue path.
        ProposalDetails details = proposal != null ? proposal : ProposalDetails.selfInsured();
        UUID lifeAssuredPartyId = details.resolveLifeAssured(applicantPartyId);
        // Validated the same way the applicant is, and for the same reason: a case naming
        // a life assured who does not exist in this tenant is unassessable, and
        // PartyApi.getParty already refuses cross-tenant reads.
        if (!lifeAssuredPartyId.equals(applicantPartyId)) {
            partyApi.getParty(lifeAssuredPartyId);
        }

        rejectTermOutsideProductBounds(productVersionId, details.requestedTermMonths());
        rejectMalformedNominations(details.beneficiaries());

        UnderwritingCase underwritingCase = new UnderwritingCase(tenantId, applicantPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency, agentOfRecordId, openedBy);
        underwritingCase.recordProposal(nextProposalNumber(), lifeAssuredPartyId, details);
        underwritingCaseRepository.save(underwritingCase);

        for (BeneficiaryNomination nomination : details.beneficiaries()) {
            proposalBeneficiaryRepository.save(new ProposalBeneficiary(tenantId, underwritingCase.getCaseId(),
                nomination.type().name(), nomination.partyId(), nomination.freeformDesignee(),
                nomination.sharePercent(), nomination.revocable()));
        }
        // With nominations: the caller just supplied them, and handing back a view that says
        // the case has none would be actively misleading.
        return toViewWithNominations(underwritingCase);
    }

    /**
     * A proposal may not ask for a term the product does not sell.
     *
     * <p>These bounds have existed on {@code ProductVersion} since Build 3 and were enforced in
     * exactly one place: {@code issueGates} on the console's manual issue form, against a term
     * typed there. The case carried no term, so on the normal path a risk was assessed, decided
     * and issued without the product's own term rules ever being consulted — the check sat
     * downstream of the decision, on a screen the automatic path never visits.
     *
     * <p>Checked at openCase rather than at decide, because this is where the proposal is
     * recorded and where the applicant can still be told. The case locks
     * {@code productVersionId}, so the bounds cannot move underneath it afterwards.
     *
     * <p>A null term is not a violation. A whole life policy, an annuity and an annually
     * renewable group scheme all genuinely have none.
     */
    private void rejectTermOutsideProductBounds(UUID productVersionId, Integer requestedTermMonths) {
        if (requestedTermMonths == null) return;

        EligibilityBounds bounds = productApi.getSnapshotByVersionId(productVersionId).eligibility();
        if (bounds == null) return;

        if (bounds.minTermMonths() != null && requestedTermMonths < bounds.minTermMonths()) {
            throw new UnderwritingValidationException("A term of " + requestedTermMonths
                + " months is below this product's minimum of " + bounds.minTermMonths());
        }
        if (bounds.maxTermMonths() != null && requestedTermMonths > bounds.maxTermMonths()) {
            throw new UnderwritingValidationException("A term of " + requestedTermMonths
                + " months is above this product's maximum of " + bounds.maxTermMonths());
        }
    }

    /**
     * The rules the column checks cannot reach.
     *
     * <p>Mirrors {@code PolicyApiImpl.validateAndBuildBeneficiaries}, so a nomination that would
     * be refused as a policy beneficiary is refused as a proposal one — otherwise a proposal
     * could record a designation that quietly fails to become anything at issuance, in a
     * listener that swallows its exceptions to a log line.
     *
     * <p>An EMPTY list is valid and means nobody was nominated, which is routine. A non-empty
     * one must total exactly 100.
     */
    private void rejectMalformedNominations(List<BeneficiaryNomination> nominations) {
        if (nominations.isEmpty()) return;

        BigDecimal total = BigDecimal.ZERO;
        for (BeneficiaryNomination nomination : nominations) {
            if (nomination.type() == null) {
                throw new UnderwritingValidationException("Each beneficiary nomination needs a type");
            }
            boolean hasParty = nomination.partyId() != null;
            boolean hasFreeform = nomination.freeformDesignee() != null && !nomination.freeformDesignee().isBlank();
            if (nomination.type() == NominationType.PARTY && (!hasParty || hasFreeform)) {
                throw new UnderwritingValidationException(
                    "A PARTY nomination needs a partyId and no freeform designee");
            }
            if (nomination.type() == NominationType.FREEFORM && (!hasFreeform || hasParty)) {
                throw new UnderwritingValidationException(
                    "A FREEFORM nomination needs a designee and no partyId");
            }
            // Validated like the applicant and the life assured, and for the same reason: a
            // nomination naming somebody who does not exist in this tenant cannot be paid.
            if (hasParty) {
                partyApi.getParty(nomination.partyId());
            }
            if (nomination.sharePercent() == null) {
                throw new UnderwritingValidationException("Each beneficiary nomination needs a share");
            }
            total = total.add(nomination.sharePercent());
        }
        if (total.compareTo(new BigDecimal("100")) != 0) {
            throw new UnderwritingValidationException(
                "Beneficiary shares must total 100, not " + total.stripTrailingZeros().toPlainString());
        }
    }

    /**
     * A human handle for a proposal, shaped like {@code policy_number}.
     *
     * <p>Random rather than sequential, matching how policy numbers are already minted
     * here. A per-tenant sequence would read better (PRO-2026-000123) but leaks case
     * volume across tenants through a shared sequence, and the actual requirement is
     * something a person can quote over the phone — which "PRO-A3F91B2C" satisfies and a
     * bare UUID does not. {@code ux_underwriting_case_proposal_number} is the uniqueness
     * guarantee.
     */
    private static String nextProposalNumber() {
        return "PRO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Override
    @Transactional
    public UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy) {
        UUID tenantId = TenantContext.get();
        UnderwritingCase underwritingCase = findOrThrow(caseId, tenantId);
        // A decided case is closed to further evidence: reworking one means reversing a
        // decision somebody made and, on an acceptance, a policy that is already in force.
        // Re-opening is a deliberate act and is not modelled yet.
        //
        // POSTPONED IS THE EXCEPTION, and always should have been. It is the one outcome that
        // means "not decided yet -- come back with more evidence", and treating it as final
        // made it the only outcome that could never be resolved: the case locked, and no
        // amount of further evidence could be submitted against it. A postponed case is work
        // in progress wearing a terminal status.
        if (isDecided(underwritingCase) && !DecisionOutcome.POSTPONED.name().equals(underwritingCase.getDecisionOutcome())) {
            throw new UnderwritingCaseAlreadyDecidedException(caseId);
        }
        underwritingCase.markInReview();

        RiskAssessment assessment = new RiskAssessment(tenantId, caseId, assessmentType.name(), assessedBy, findings, riskScore);
        riskAssessmentRepository.save(assessment);

        // ADVICE ONLY. UnderwritingDecisionMade is published by decide(...) and nowhere else.
        //
        // This line used to be decideIfPossible(...), and the event was published from right
        // here -- so submitting an assessment ran a placeholder engine, wrote its verdict into
        // the decision columns, and put a real policy in force, with no person involved at any
        // point. An assessment is evidence; it must never issue a contract.
        recommendFromEvidence(underwritingCase);
        underwritingCaseRepository.save(underwritingCase);
        return toView(underwritingCase);
    }

    @Override
    @Transactional
    public UnderwritingCaseView decide(UUID caseId, DecisionInput decision, String decidedBy,
                                        boolean callerIsSeniorUnderwriter) {
        UUID tenantId = TenantContext.get();
        UnderwritingCase underwritingCase = findOrThrow(caseId, tenantId);

        if (isDecided(underwritingCase) && !DecisionOutcome.POSTPONED.name().equals(underwritingCase.getDecisionOutcome())) {
            throw new UnderwritingCaseAlreadyDecidedException(caseId);
        }
        if (decision.reason() == null || decision.reason().isBlank()) {
            throw new UnderwritingValidationException("A decision must carry a reason");
        }
        // Evidence first. Nothing structural stopped a case being decided the instant it was
        // opened, and "accepted, nothing assessed" is not a decision anyone can defend later.
        if (riskAssessmentRepository.countByTenantIdAndCaseId(tenantId, caseId) == 0) {
            throw new UnderwritingValidationException(
                "Case " + caseId + " has no assessment -- there is nothing to decide on");
        }
        boolean loaded = decision.outcome() == DecisionOutcome.LOADED;
        if (loaded && decision.loadingPercent() == null) {
            throw new UnderwritingValidationException("A LOADED decision must carry a loading percent");
        }
        if (!loaded && decision.loadingPercent() != null) {
            throw new UnderwritingValidationException(
                "A loading percent is only meaningful on a LOADED decision, not " + decision.outcome());
        }

        // An ABSENT recommendation is not a disagreement. A pre-V5 case has none, and a case
        // decided POSTPONED and then re-assessed may be decided again before the engine has
        // spoken. Neither should demand a senior.
        String recommended = underwritingCase.getRecommendationOutcome();
        boolean overrode = recommended != null && !recommended.equals(decision.outcome().name());
        if (overrode && !callerIsSeniorUnderwriter) {
            throw new SeniorUnderwriterApprovalRequiredException(caseId, recommended, decision.outcome().name());
        }

        // decision_decline_reason is the column's name and DECLINED/POSTPONED are what it was
        // built for -- what the applicant is eventually told. On an ACCEPT or a LOADED the
        // reason is internal justification, and putting it in a field named "decline reason"
        // is how it ends up on a customer's letter.
        String declineReason = decision.outcome() == DecisionOutcome.DECLINED
            || decision.outcome() == DecisionOutcome.POSTPONED ? decision.reason() : null;
        underwritingCase.recordDecision(decision.outcome().name(), decision.loadingPercent(),
            declineReason, decidedBy, overrode);
        underwritingCaseRepository.save(underwritingCase);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("outcome", underwritingCase.getDecisionOutcome());
        // loadingPercent is genuinely nullable (only set when outcome=LOADED per the
        // chk_loading_only_when_loaded DB CHECK) -- Map.of(...) would throw NPE here for
        // every other outcome, hence the mutable map.
        payload.put("loadingPercent", underwritingCase.getDecisionLoadingPercent());
        payload.put("decidedAt", underwritingCase.getDecisionDecidedAt().toString());
        payload.put("decidedBy", decidedBy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("underwriting.UnderwritingDecisionMade", tenantId, payload));

        return toView(underwritingCase);
    }

    @Override
    @Transactional
    public MedicalDisclosureView recordDisclosures(UUID caseId, List<DisclosureAnswer> answers, String recordedBy) {
        UUID tenantId = TenantContext.get();
        // Confirms the case exists in this tenant before writing a child row against it --
        // same anti-enumeration shape as everywhere else in this class.
        findOrThrow(caseId, tenantId);
        if (answers == null || answers.isEmpty()) {
            throw new UnderwritingValidationException("A disclosure set must contain at least one answer");
        }

        MedicalDisclosure disclosure = new MedicalDisclosure(tenantId, caseId, writeJson(answers), recordedBy);
        medicalDisclosureRepository.save(disclosure);
        return toDisclosureView(disclosure);
    }

    @Override
    public List<MedicalDisclosureView> listDisclosures(UUID caseId) {
        UUID tenantId = TenantContext.get();
        findOrThrow(caseId, tenantId);
        return medicalDisclosureRepository
            .findByTenantIdAndCaseIdOrderByCreatedAtAscMedicalDisclosureIdAsc(tenantId, caseId)
            .stream()
            .map(this::toDisclosureView)
            .toList();
    }

    private MedicalDisclosureView toDisclosureView(MedicalDisclosure disclosure) {
        return new MedicalDisclosureView(disclosure.getMedicalDisclosureId(), disclosure.getCaseId(),
            readJson(disclosure.getQuestionResponseSet()), disclosure.getRecordedBy(), disclosure.getCreatedAt());
    }

    /**
     * The Q&A set is a JSONB column holding a JSON array, because the question set is
     * product-specific and this module does not own it. Serialised here rather than mapped to a
     * child table for the same reason: a table would impose a shape the platform has no authority
     * to define.
     *
     * <p>A serialisation failure is not swallowed. Losing a disclosure quietly is the one outcome
     * this whole feature exists to prevent.
     */
    private String writeJson(List<DisclosureAnswer> answers) {
        try {
            return objectMapper.writeValueAsString(answers);
        } catch (JsonProcessingException e) {
            throw new UnderwritingValidationException("Could not record disclosures: " + e.getOriginalMessage());
        }
    }

    private List<DisclosureAnswer> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<DisclosureAnswer>>() {});
        } catch (JsonProcessingException e) {
            // Stored JSON that no longer parses means the shape changed under a row that already
            // exists. Failing loudly beats returning an empty list, which would read as "nothing
            // was disclosed" -- the most misleading answer this endpoint could give.
            throw new IllegalStateException("Stored disclosure set is not readable as answers", e);
        }
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

    /**
     * Runs the rules engine over every assessment on the case and records its opinion.
     *
     * <p>Was {@code decideIfPossible}, which despite the name had no condition and always
     * decided. So the FIRST assessment settled the case whatever type it was: a case needing
     * medical AND financial AND occupational review was decided by whichever arrived first,
     * and a clean medical alone put a policy in force before anybody looked at the applicant's
     * occupation. It now recomputes advice each time evidence arrives, and a person decides.
     */
    private void recommendFromEvidence(UnderwritingCase underwritingCase) {
        String sumAssuredBand = resolveSumAssuredBand(underwritingCase.getSumAssuredAmount());

        BigDecimal ageMultiplier = resolveAgeMultiplier(underwritingCase);
        BigDecimal sumAssuredMultiplier = productApi.resolveRatingMultiplier(underwritingCase.getProductVersionId(), FactorType.SUM_ASSURED_BAND, sumAssuredBand);

        List<BigDecimal> riskScores = latestScorePerAssessmentType(underwritingCase.getCaseId());

        RiskProfile profile = new RiskProfile(ageMultiplier, sumAssuredMultiplier, riskScores);
        UnderwritingDecision recommendation = rulesEnginePort.evaluate(profile);

        underwritingCase.recordRecommendation(
            recommendation.outcome().name(), recommendation.loadingPercent(), recommendation.reason());
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

    /**
     * The single-case read, and the one the issuance listener uses — so this is the read that
     * must carry the beneficiary nominations, or an automatically issued policy goes in force
     * with nobody named on it.
     */
    @Override
    public UnderwritingCaseView getCase(UUID caseId) {
        return toViewWithNominations(findOrThrow(caseId, TenantContext.get()));
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
            c.getSumAssuredAmount(), c.getSumAssuredCurrency(), c.getAgentOfRecordId(),
            c.getProposalNumber(), c.getLifeAssuredPartyId(), c.getBranch(), c.getSourceOfBusiness(),
            c.getProposedCommencementDate(),
            c.getRecommendationOutcome() != null ? DecisionOutcome.valueOf(c.getRecommendationOutcome()) : null,
            c.getRecommendationLoadingPercent(), c.getRecommendationReason(),
            c.getDecisionDecidedBy(), c.isDecisionOverrodeRecommendation(),
            c.getRequestedTermMonths(), c.getPremiumPayingTermMonths(), c.getPremiumFrequency(),
            List.of());
    }

    /**
     * A view carrying the case's beneficiary nominations, for the single-case reads.
     *
     * <p>Deliberately NOT folded into {@link #toView}. That one builds every row of
     * {@code listCases}, and fetching nominations there would be a query per case — twenty
     * extra round trips to populate a field the queue does not display. So a list view carries
     * an EMPTY nomination list, which is a real hazard worth stating plainly: empty means "not
     * loaded here", not "nobody was nominated. Only the reads below promise them, and the
     * issuance listener uses {@link #getCase}, which does.
     */
    private UnderwritingCaseView toViewWithNominations(UnderwritingCase c) {
        List<BeneficiaryNomination> nominations = proposalBeneficiaryRepository
            .findByTenantIdAndCaseIdOrderByCreatedAtAsc(c.getTenantId(), c.getCaseId())
            .stream()
            .map(b -> new BeneficiaryNomination(NominationType.valueOf(b.getBeneficiaryType()),
                b.getPartyId(), b.getFreeformDesignee(), b.getSharePercent(), b.isRevocable()))
            .toList();

        UnderwritingCaseView base = toView(c);
        return new UnderwritingCaseView(base.caseId(), base.applicantPartyId(), base.productId(),
            base.productVersionId(), base.status(), base.referralStatus(), base.decisionOutcome(),
            base.decisionLoadingPercent(), base.decisionDeclineReason(), base.decisionDecidedAt(),
            base.sumAssuredAmount(), base.sumAssuredCurrency(), base.agentOfRecordId(),
            base.proposalNumber(), base.lifeAssuredPartyId(), base.branch(), base.sourceOfBusiness(),
            base.proposedCommencementDate(), base.recommendationOutcome(),
            base.recommendationLoadingPercent(), base.recommendationReason(),
            base.decisionDecidedBy(), base.decisionOverrodeRecommendation(),
            base.requestedTermMonths(), base.premiumPayingTermMonths(), base.premiumFrequency(),
            nominations);
    }
}
