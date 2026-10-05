package tz.co.nlolo.lifeplatform.underwriting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.party.api.PartyType;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.underwriting.api.*;
import tz.co.nlolo.lifeplatform.underwriting.domain.*;
import tz.co.nlolo.lifeplatform.product.api.AnnuityForm;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPlan;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPricingInput;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPricingRefusedException;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.AnnuityChoiceRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.DeferredAnnuityChoiceRepository;
import tz.co.nlolo.lifeplatform.product.api.VestingTerms;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.MedicalDisclosureRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.ProposalBeneficiaryRepository;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.ProposalGroupSchemeRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.ProposalGroupGradeRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.ProposalGroupMemberRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.RiskAssessmentRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnderwritingCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final DistributionApi distributionApi;
    private final ReferenceDataApi referenceDataApi;
    private final RulesEnginePort rulesEnginePort;
    private final ApplicationEventPublisher eventPublisher;
    private final MedicalDisclosureRepository medicalDisclosureRepository;
    private final ProposalBeneficiaryRepository proposalBeneficiaryRepository;
    private final ProposalGroupSchemeRepository proposalGroupSchemeRepository;
    private final ProposalGroupGradeRepository proposalGroupGradeRepository;
    private final ProposalGroupMemberRepository proposalGroupMemberRepository;
    private final AnnuityChoiceRepository annuityChoiceRepository;
    private final DeferredAnnuityChoiceRepository deferredAnnuityChoiceRepository;
    private final FuneralApplications funeralApplications;
    /** "Today" is the civil date here, never UTC's (the UTC-vs-civil day bug). */
    private static final java.time.ZoneId CIVIL_ZONE = java.time.ZoneId.of("Africa/Dar_es_Salaam");
    /** Serialises the disclosure Q&A set into its JSONB column -- see recordDisclosures. */
    private final ObjectMapper objectMapper;

    public UnderwritingApiImpl(UnderwritingCaseRepository underwritingCaseRepository, RiskAssessmentRepository riskAssessmentRepository,
                                PartyApi partyApi, ProductApi productApi, DistributionApi distributionApi,
                                ReferenceDataApi referenceDataApi, RulesEnginePort rulesEnginePort,
                                ApplicationEventPublisher eventPublisher,
                                MedicalDisclosureRepository medicalDisclosureRepository, ObjectMapper objectMapper,
                                ProposalBeneficiaryRepository proposalBeneficiaryRepository,
                                ProposalGroupSchemeRepository proposalGroupSchemeRepository,
                                ProposalGroupGradeRepository proposalGroupGradeRepository,
                                ProposalGroupMemberRepository proposalGroupMemberRepository,
                                AnnuityChoiceRepository annuityChoiceRepository,
                                DeferredAnnuityChoiceRepository deferredAnnuityChoiceRepository,
                                FuneralApplications funeralApplications) {
        this.funeralApplications = funeralApplications;
        this.annuityChoiceRepository = annuityChoiceRepository;
        this.deferredAnnuityChoiceRepository = deferredAnnuityChoiceRepository;
        this.proposalBeneficiaryRepository = proposalBeneficiaryRepository;
        this.proposalGroupSchemeRepository = proposalGroupSchemeRepository;
        this.proposalGroupGradeRepository = proposalGroupGradeRepository;
        this.proposalGroupMemberRepository = proposalGroupMemberRepository;
        this.underwritingCaseRepository = underwritingCaseRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.distributionApi = distributionApi;
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
    /**
     * A named agent of record must be a real agent in this tenant.
     *
     * <p>Null is untouched: a direct sale names nobody, and forcing an agent would invent a
     * commission payee.
     *
     * <p>422 rather than 404 -- the agent is a field on a submitted case, not the thing being
     * addressed -- and refused HERE rather than at issuance because a case's agent of record
     * cannot be corrected afterwards. Letting an unknown id through would produce a case that
     * passes underwriting, is accepted, and then fails issuance permanently.
     */
    private void requireRealAgent(UUID agentOfRecordId) {
        if (agentOfRecordId == null) {
            return;
        }
        if (distributionApi.getAgentIfPresent(agentOfRecordId).isEmpty()) {
            throw new UnderwritingValidationException("Agent of record " + agentOfRecordId
                + " is not an agent in this tenant; a case naming an unknown agent cannot be issued");
        }
    }

    @Override
    @Transactional
    public UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, UUID agentOfRecordId, String openedBy) {
        return openCase(applicantPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency,
            agentOfRecordId, ProposalDetails.selfInsured(), openedBy);
    }

    @Override
    @Transactional
    public UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, UUID agentOfRecordId, ProposalDetails proposal, String openedBy) {
        return openIndividualCase(applicantPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency,
            agentOfRecordId, proposal, openedBy, null, null);
    }

    @Override
    @Transactional
    public UnderwritingCaseView openMemberEvidenceCase(UUID memberPartyId, UUID productId, UUID productVersionId,
                                                       BigDecimal benefitAmount, String currency,
                                                       UUID agentOfRecordId, String policyNumber,
                                                       UUID policyMemberId, String openedBy) {
        if (policyNumber == null || policyMemberId == null) {
            throw new UnderwritingValidationException("An evidence case names the scheme and the member it is for");
        }
        return openIndividualCase(memberPartyId, productId, productVersionId, benefitAmount, currency,
            agentOfRecordId, ProposalDetails.selfInsured(), openedBy, policyNumber, policyMemberId);
    }

    /** @param memberEvidence the free-cover-limit case for one scheme member, the one individual
     *  case a scheme product may carry -- see {@link UnderwritingApi#openMemberEvidenceCase}. */
    private UnderwritingCaseView openIndividualCase(UUID applicantPartyId, UUID productId, UUID productVersionId,
                                                    BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                                                    UUID agentOfRecordId, ProposalDetails proposal, String openedBy,
                                                    String evidenceForPolicyNumber, UUID evidenceForMemberId) {
        boolean memberEvidence = evidenceForPolicyNumber != null;
        UUID tenantId = TenantContext.get();
        // Confirms the applicant party genuinely exists and belongs to this tenant --
        // PartyApi.getParty already throws PartyNotFoundException on cross-tenant access
        // (M1's anti-enumeration pattern), which is exactly the failure mode we want here too.
        PartyView applicant = partyApi.getParty(applicantPartyId);

        // CORRECTION: agentOfRecordId used to be stored as an opaque id and NOT validated
        // against distribution, on the grounds that this module need only carry it through to
        // issuance. Issuance now refuses an agent of record that is not an agent, and there is
        // no endpoint to correct a case's agent -- so an unchecked id here produces a case that
        // is accepted and can then never be issued. Checked at the boundary instead, where
        // whoever supplied it is still on the form, exactly as the applicant is checked above.
        requireRealAgent(agentOfRecordId);

        ProposalDetails details = proposal != null ? proposal : ProposalDetails.selfInsured();
        UUID lifeAssuredPartyId = details.resolveLifeAssured(applicantPartyId);
        // Validated the same way the applicant is, and for the same reason: a case naming
        // a life assured who does not exist in this tenant is unassessable, and
        // PartyApi.getParty already refuses cross-tenant reads.
        PartyView lifeAssured = lifeAssuredPartyId.equals(applicantPartyId)
            ? applicant : partyApi.getParty(lifeAssuredPartyId);

        // AN INDIVIDUAL CASE INSURES ONE PERSON. Nothing checked either half of that, so a
        // GROUP_LIFE product was proposed here with the corporate client itself as the "life
        // assured", decided, and issued as a single-life policy covering no members -- a group
        // contract with no schedule, which the client page then could not load. A scheme is
        // proposed through the group path, a credit-life book through its scheme set-up; both
        // of those know what a member is. The exception is a member's own evidence case, opened by
        // policy for the excess over the free cover limit on a scheme that already exists.
        ProductCategory category = productApi.getSnapshotByVersionId(productVersionId).category();
        if (!memberEvidence && (category == ProductCategory.GROUP_LIFE || category == ProductCategory.CREDIT_LIFE)) {
            throw new UnderwritingValidationException("A " + category + " product is not proposed as a single life"
                + " -- set it up as a " + (category == ProductCategory.GROUP_LIFE ? "group scheme" : "credit-life scheme"));
        }
        if (lifeAssured.partyType() != PartyType.INDIVIDUAL) {
            // No date of birth, no sex, no health: nothing about a company can be underwritten
            // as a life. It may be the APPLICANT (key-person cover), never the life assured.
            throw new UnderwritingValidationException(
                "The life assured must be a person; " + lifeAssured.displayName() + " is " + lifeAssured.partyType());
        }

        rejectTermOutsideProductBounds(productVersionId, details.requestedTermMonths());
        rejectMalformedNominations(details.beneficiaries());

        UnderwritingCase underwritingCase = new UnderwritingCase(tenantId, applicantPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency, agentOfRecordId, openedBy);
        underwritingCase.recordProposal(nextProposalNumber(), lifeAssuredPartyId, details);
        if (memberEvidence) {
            underwritingCase.recordEvidenceFor(evidenceForPolicyNumber, evidenceForMemberId);
        }
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

    @Override
    @Transactional
    public UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId,
                                          UUID agentOfRecordId, GroupProposal proposal, String openedBy) {
        UUID tenantId = TenantContext.get();
        partyApi.getParty(applicantPartyId);
        // Same check as the individual path: a broker named on a scheme must be a real agent,
        // or the scheme is decided and can then never be issued.
        requireRealAgent(agentOfRecordId);

        if (proposal == null || proposal.openingSchedule() == null || proposal.openingSchedule().isEmpty()) {
            // The rule issueGroupScheme already enforces, moved to where the proposal is taken.
            // A scheme's sum assured IS the total of its schedule, so an empty one is a
            // contract insuring nobody for nothing -- and refusing it here means it never
            // reaches an underwriter's queue in the first place.
            throw new UnderwritingValidationException("A group proposal names at least one life");
        }
        ProductCategory category = productApi.getSnapshotByVersionId(productVersionId).category();
        if (category != ProductCategory.GROUP_LIFE) {
            // The mirror of issueGroupScheme's own check, applied at proposal time so a case
            // cannot be decided into an issuance that will then refuse it -- which would leave
            // a DECIDED case with no policy and no explanation anywhere.
            throw new UnderwritingValidationException(
                "A group proposal needs a GROUP_LIFE product; this one is " + category);
        }

        // SUM ASSURED IS NULL ON A GROUP CASE, and that is a decision rather than an omission.
        //
        // Valuing the schedule needs GroupBenefitCalculator -- flat, salary x multiple, or a
        // grade lookup, each rounded once -- and that lives in policy.domain, which this module
        // may not reach. Re-implementing it here would put benefit arithmetic in two modules
        // and let them drift, which is the one thing money arithmetic must never do. (The
        // console's groupBenefitPreview.ts mirrors it deliberately, but it is labelled a
        // preview, it sends nothing, and both sides run the same worked examples against each
        // other. A second SERVER-side copy would have no such safety net.)
        //
        // So the case says what is being ASKED FOR -- the basis, the schedule, the count -- and
        // the sum assured appears when policy derives it at issuance, in the one place that
        // owns it.
        UnderwritingCase underwritingCase = new UnderwritingCase(tenantId, applicantPartyId, productId,
            productVersionId, null, proposal.currency(), agentOfRecordId, openedBy);
        underwritingCase.recordGroupProposal(nextProposalNumber(), proposal.commencementDate(),
            proposal.premiumFrequency());
        underwritingCaseRepository.save(underwritingCase);
        persistGroupProposal(tenantId, underwritingCase.getCaseId(), proposal, openedBy);

        return toViewWithNominations(underwritingCase);
    }

    /**
     * The proposal's three tables, written in one go.
     *
     * <p>Mirrors how {@code openCase} already persists {@code ProposalBeneficiary} rows: the
     * case row first, then its children, all inside the caller's transaction.
     */
    private void persistGroupProposal(UUID tenantId, UUID caseId, GroupProposal proposal, String createdBy) {
        proposalGroupSchemeRepository.save(new ProposalGroupScheme(tenantId, caseId,
            proposal.benefitBasis().name(), proposal.flatBenefitAmount(), proposal.salaryMultiple(),
            proposal.fclAmount(), proposal.currency(),
            proposal.premiumAmount(), proposal.premiumCurrency(), proposal.premiumFrequency(),
            proposal.commencementDate(), proposal.policyTermMonths(), createdBy));

        for (GroupProposal.GradeLine grade : proposal.grades()) {
            proposalGroupGradeRepository.save(
                new ProposalGroupGrade(tenantId, caseId, grade.gradeCode(), grade.benefitAmount()));
        }
        for (GroupProposal.MemberLine line : proposal.openingSchedule()) {
            // Each life validated the same way the applicant is, and for the same reason: a
            // schedule naming somebody who does not exist in this tenant is unassessable, and
            // PartyApi.getParty already refuses cross-tenant reads.
            partyApi.getParty(line.memberPartyId());
            proposalGroupMemberRepository.save(new ProposalGroupMember(tenantId, caseId,
                line.memberPartyId(), line.gradeCode(), line.salaryAmount()));
        }
    }

    /** The proposal on a case, or null if it is individual business. */
    private GroupProposal groupProposalFor(UUID caseId, UUID tenantId) {
        return proposalGroupSchemeRepository.findByCaseIdAndTenantId(caseId, tenantId)
            .map(scheme -> new GroupProposal(
                GroupBenefitBasis.valueOf(scheme.getBenefitBasis()),
                scheme.getFlatBenefitAmount(), scheme.getSalaryMultiple(), scheme.getFclAmount(),
                scheme.getCurrency(),
                proposalGroupGradeRepository.findByTenantIdAndCaseIdOrderByCreatedAtAsc(tenantId, caseId)
                    .stream()
                    .map(g -> new GroupProposal.GradeLine(g.getGradeCode(), g.getBenefitAmount()))
                    .toList(),
                proposalGroupMemberRepository
                    .findByTenantIdAndCaseIdOrderByCreatedAtAscProposalGroupMemberIdAsc(tenantId, caseId)
                    .stream()
                    .map(m -> new GroupProposal.MemberLine(m.getMemberPartyId(), m.getGradeCode(), m.getSalaryAmount()))
                    .toList(),
                scheme.getPremiumAmount(), scheme.getPremiumCurrency(), scheme.getPremiumFrequency(),
                scheme.getCommencementDate(), scheme.getPolicyTermMonths()))
            .orElse(null);
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
        // An annuity takes the light path (product step 5): its risk is the annuitant living LONG,
        // so medical evidence matters little and proof of age -- which picks the rate -- is the
        // evidence. Product is asked first; only an ANNUITY version reads the choice table.
        AnnuityPlan annuityPlan = productApi.resolveAnnuityPlan(underwritingCase.getProductVersionId());
        boolean annuity = annuityPlan.annuity();
        if (annuity) {
            checkAnnuityDecision(underwritingCase, decision, annuityPlan);
        }
        // A funeral plan is priced by its table (plan R2), and accepted only with a family that still prices.
        if (!annuity && funeralApplications.isFuneral(underwritingCase)) {
            funeralApplications.checkDecision(underwritingCase, decision.outcome());
        }
        // Evidence first. Nothing structural stopped a case being decided the instant it was
        // opened, and "accepted, nothing assessed" is not a decision anyone can defend later.
        if (!annuity && riskAssessmentRepository.countByTenantIdAndCaseId(tenantId, caseId) == 0) {
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
        // A scheme member's evidence case grants or refuses the excess, and nothing else. A
        // loading needs a premium to load, and one member of a scheme has none of their own --
        // the scheme's premium was agreed for the whole schedule and changes at renewal.
        if (loaded && underwritingCase.getEvidenceForPolicyNumber() != null) {
            throw new UnderwritingValidationException("Case " + caseId + " is evidence for a member of "
                + underwritingCase.getEvidenceForPolicyNumber() + ": accept or decline the excess; a member"
                + " of a scheme has no premium of their own to load");
        }

        // SEPARATION OF DUTIES, as claims enforces between its assessor and its decider. The
        // underwriter role says who MAY decide; it cannot say that the one deciding is not the
        // one who captured the proposal or wrote its evidence. Without this a single login could
        // open, assess and accept a case -- and one did, in twenty seconds, issuing a single-life
        // policy on a group product to a corporate "life assured" nobody else ever looked at.
        if (decidedBy != null && decidedBy.equals(underwritingCase.getCreatedBy())) {
            throw new UnderwritingSeparationOfDutiesException(caseId, "opened");
        }
        if (riskAssessmentRepository.existsByTenantIdAndCaseIdAndAssessor(tenantId, caseId, decidedBy)) {
            throw new UnderwritingSeparationOfDutiesException(caseId, "assessed");
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
        if (annuity && annuityPlan.deferred() && decision.outcome() == DecisionOutcome.ACCEPT) {
            // Spec Q8: the date of birth and sex whose proof was seen, so vesting re-confirms age
            // only if the party record has changed since.
            PartyDetailView lifeAssured = partyApi.getPartyDetail(lifeAssuredPartyId(underwritingCase));
            deferredAnnuityChoiceRepository.findById(caseId).ifPresent(choice -> {
                choice.confirmAgeEvidence(decidedBy, lifeAssured.dateOfBirth(),
                    lifeAssured.sex() != null ? lifeAssured.sex().name() : null);
                deferredAnnuityChoiceRepository.save(choice);
            });
        } else if (annuity && decision.outcome() == DecisionOutcome.ACCEPT) {
            annuityChoiceRepository.findById(caseId).ifPresent(choice -> {
                choice.confirmAgeEvidence(decidedBy);
                annuityChoiceRepository.save(choice);
            });
        }

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

    /**
     * An annuity is accepted or declined, never loaded (its premium is the purchase price, so there is
     * nothing to load) or postponed (a priced quote would dangle) -- plan R4. Acceptance needs the
     * choice, confirmed proof of age, and a purchase that PRICES today: re-quoted here so a refusal
     * surfaces before any money is taken, not at the lock.
     */
    private void checkAnnuityDecision(UnderwritingCase underwritingCase, DecisionInput decision, AnnuityPlan plan) {
        if (decision.outcome() != DecisionOutcome.ACCEPT && decision.outcome() != DecisionOutcome.DECLINED) {
            throw new UnderwritingValidationException("An annuity is accepted or declined; it is not loaded or postponed");
        }
        if (decision.outcome() == DecisionOutcome.DECLINED) {
            return;
        }
        if (plan.deferred()) {
            checkDeferredAcceptance(underwritingCase, decision);
            return;
        }
        AnnuityChoiceEntity choice = annuityChoiceRepository.findById(underwritingCase.getCaseId())
            .orElseThrow(() -> new UnderwritingValidationException(
                "An annuity case must record the chosen form and frequency before it is decided"));
        if (!decision.ageEvidenceConfirmed()) {
            throw new UnderwritingValidationException("An annuity is accepted only once proof of age is confirmed");
        }
        AnnuityChoice chosen = choice.toChoice();
        PartyDetailView annuitant = partyApi.getPartyDetail(lifeAssuredPartyId(underwritingCase));
        PartyDetailView joint = chosen.jointLifePartyId() != null ? partyApi.getPartyDetail(chosen.jointLifePartyId()) : null;
        try {
            productApi.priceAnnuity(underwritingCase.getProductVersionId(), new AnnuityPricingInput(
                chosen.formCode(), chosen.frequency(), underwritingCase.getSumAssuredAmount(),
                annuitant.dateOfBirth(), annuitant.sex() != null ? annuitant.sex().name() : null,
                joint != null ? joint.dateOfBirth() : null, joint != null && joint.sex() != null ? joint.sex().name() : null,
                LocalDate.now(CIVIL_ZONE)));
        } catch (AnnuityPricingRefusedException e) {
            throw new UnderwritingValidationException(e.getMessage());
        }
    }

    /**
     * A deferred annuity is accepted on proof of age alone and is NOT priced now (spec Q7): it prices
     * at vesting, at the rates in force that day. What it needs is a retirement age, so it has a
     * vesting date, and a recorded date of birth to measure it from. The confirmed date of birth and
     * sex are stored once the decision is recorded (decide), beside D1's own confirmation.
     */
    private void checkDeferredAcceptance(UnderwritingCase underwritingCase, DecisionInput decision) {
        if (deferredAnnuityChoiceRepository.findById(underwritingCase.getCaseId()).isEmpty()) {
            throw new UnderwritingValidationException(
                "A deferred annuity case must record the retirement age before it is decided");
        }
        if (!decision.ageEvidenceConfirmed()) {
            throw new UnderwritingValidationException("An annuity is accepted only once proof of age is confirmed");
        }
        if (partyApi.getPartyDetail(lifeAssuredPartyId(underwritingCase)).dateOfBirth() == null) {
            throw new UnderwritingValidationException("The applicant's date of birth is not recorded, so there is no vesting date");
        }
    }

    @Override
    @Transactional
    public DeferredAnnuityChoice recordDeferredAnnuityChoice(UUID caseId, int retirementAge, String recordedBy) {
        UUID tenantId = TenantContext.get();
        UnderwritingCase underwritingCase = findOrThrow(caseId, tenantId);
        AnnuityPlan plan = productApi.resolveAnnuityPlan(underwritingCase.getProductVersionId());
        if (!plan.deferred()) {
            throw new UnderwritingValidationException("Only a deferred annuity case records a retirement age");
        }
        if (isDecided(underwritingCase)) {
            throw new UnderwritingCaseAlreadyDecidedException(caseId);
        }
        LocalDate dateOfBirth = partyApi.getPartyDetail(lifeAssuredPartyId(underwritingCase)).dateOfBirth();
        if (dateOfBirth == null) {
            throw new UnderwritingValidationException("The applicant's date of birth is not recorded, so there is no vesting date");
        }
        VestingTerms vesting = plan.vesting();
        if (retirementAge < vesting.minVestingAge() || retirementAge > vesting.maxVestingAge()) {
            throw new UnderwritingValidationException("The retirement age must be within the vesting window, "
                + vesting.minVestingAge() + " to " + vesting.maxVestingAge());
        }
        if (!dateOfBirth.plusYears(retirementAge).isAfter(LocalDate.now(CIVIL_ZONE))) {
            throw new UnderwritingValidationException("A retirement age of " + retirementAge + " has already been reached");
        }
        DeferredAnnuityChoiceEntity entity = deferredAnnuityChoiceRepository.findById(caseId)
            .orElseGet(() -> new DeferredAnnuityChoiceEntity(tenantId, caseId));
        entity.choose(retirementAge, recordedBy);
        return deferredAnnuityChoiceRepository.save(entity).toChoice(dateOfBirth);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<DeferredAnnuityChoice> deferredAnnuityChoice(UUID caseId) {
        // Empty, never a throw -- annuityChoice's reason: callers ask inside their own transaction.
        java.util.Optional<UnderwritingCase> underwritingCase =
            underwritingCaseRepository.findByCaseIdAndTenantId(caseId, TenantContext.get());
        if (underwritingCase.isEmpty()
                || !productApi.resolveAnnuityPlan(underwritingCase.get().getProductVersionId()).deferred()) {
            return java.util.Optional.empty();
        }
        return deferredAnnuityChoiceRepository.findById(caseId).map(choice -> choice.toChoice(
            partyApi.getPartyDetail(lifeAssuredPartyId(underwritingCase.get())).dateOfBirth()));
    }

    @Override
    @Transactional
    public FuneralApplication recordFuneralApplication(UUID caseId, String planCode, List<FuneralApplication.Life> dependants,
                                                       String recordedBy) {
        UnderwritingCase underwritingCase = findOrThrow(caseId, TenantContext.get());
        if (!funeralApplications.isFuneral(underwritingCase)) {
            throw new UnderwritingValidationException("Only a funeral plan case records a funeral application");
        }
        if (isDecided(underwritingCase)) {
            throw new UnderwritingCaseAlreadyDecidedException(caseId);
        }
        return funeralApplications.record(underwritingCase, planCode, dependants, recordedBy);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<FuneralApplication> funeralApplication(UUID caseId) {
        // Empty, never a throw -- annuityChoice's reason: callers ask inside their own transaction.
        java.util.Optional<UnderwritingCase> underwritingCase =
            underwritingCaseRepository.findByCaseIdAndTenantId(caseId, TenantContext.get());
        if (underwritingCase.isEmpty() || !funeralApplications.isFuneral(underwritingCase.get())) {
            return java.util.Optional.empty();
        }
        return funeralApplications.read(underwritingCase.get());
    }

    @Override
    @Transactional
    public AnnuityChoice recordAnnuityChoice(UUID caseId, AnnuityChoice choice, String recordedBy) {
        UUID tenantId = TenantContext.get();
        UnderwritingCase underwritingCase = findOrThrow(caseId, tenantId);
        AnnuityPlan plan = productApi.resolveAnnuityPlan(underwritingCase.getProductVersionId());
        if (!plan.annuity()) {
            throw new UnderwritingValidationException("Only an annuity case records an annuity choice");
        }
        if (plan.deferred()) {
            throw new UnderwritingValidationException("A deferred annuity records a retirement age; its form is chosen when it vests");
        }
        if (isDecided(underwritingCase)) {
            throw new UnderwritingCaseAlreadyDecidedException(caseId);
        }
        AnnuityForm form = plan.form(choice.formCode()).orElseThrow(() ->
            new UnderwritingValidationException("This version does not offer form " + choice.formCode()));
        if (plan.frequency(choice.frequency()).isEmpty()) {
            throw new UnderwritingValidationException("This version does not offer " + choice.frequency() + " payments");
        }
        if (form.joint() && choice.jointLifePartyId() == null) {
            throw new UnderwritingValidationException("Form " + form.formCode() + " is joint-life: name the joint life");
        }
        if (!form.joint() && choice.jointLifePartyId() != null) {
            throw new UnderwritingValidationException("Form " + form.formCode() + " is single-life: it takes no joint life");
        }
        if (choice.jointLifePartyId() != null) {
            if (choice.jointLifePartyId().equals(lifeAssuredPartyId(underwritingCase))) {
                throw new UnderwritingValidationException("The joint life must be someone other than the annuitant");
            }
            PartyDetailView joint = partyApi.getPartyDetail(choice.jointLifePartyId());
            if (joint.partyType() != PartyType.INDIVIDUAL) {
                throw new UnderwritingValidationException("The joint life must be a person");
            }
            if (joint.dateOfBirth() == null) {
                throw new UnderwritingValidationException("The joint life's date of birth is not recorded, so there is no age to price");
            }
        }
        AnnuityChoiceEntity entity = annuityChoiceRepository.findById(caseId)
            .orElseGet(() -> new AnnuityChoiceEntity(tenantId, caseId));
        entity.choose(choice.formCode(), choice.frequency(), choice.jointLifePartyId(), recordedBy);
        return annuityChoiceRepository.save(entity).toChoice();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<AnnuityChoice> annuityChoice(UUID caseId) {
        // Empty, never a throw, for a case this tenant does not have: callers (the annuity module's
        // issue listener) ask inside their own transaction, and an exception thrown through this
        // transactional method would mark theirs rollback-only even if they caught it.
        java.util.Optional<UnderwritingCase> underwritingCase =
            underwritingCaseRepository.findByCaseIdAndTenantId(caseId, TenantContext.get());
        if (underwritingCase.isEmpty()
                || !productApi.resolveAnnuityPlan(underwritingCase.get().getProductVersionId()).annuity()) {
            return java.util.Optional.empty();
        }
        return annuityChoiceRepository.findById(caseId).map(AnnuityChoiceEntity::toChoice);
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
        // NO ENGINE OPINION ON A GROUP SCHEME, and this is a decision rather than a gap.
        //
        // RiskProfile is ageBandMultiplier x sumAssuredBandMultiplier plus assessment scores.
        // On a scheme the age band it would resolve is the APPLICANT's -- a company, whose
        // date of birth is null, so it would silently take the neutral 1.0 and dress a
        // non-answer up as a rating. Group underwriting looks at scheme size, industry, claims
        // experience and average age, none of which this platform holds, and inventing a group
        // rule here would be actuarial content nobody has signed.
        //
        // A case with no recommendation is already legal and already handled: decide()'s own
        // rule is that "An ABSENT recommendation is not a disagreement", so an underwriter
        // settles a scheme without a senior being demanded for departing from advice that was
        // never given. That is the intended behaviour, not a side effect.
        //
        // The rating multiplier is skipped with it, for the same reason: it is the product of
        // an age band and a sum assured band, and a scheme has neither -- its sum assured is
        // deliberately null until policy derives it.
        if (proposalGroupSchemeRepository.existsByCaseIdAndTenantId(
                underwritingCase.getCaseId(), underwritingCase.getTenantId())) {
            return;
        }
        BigDecimal ageMultiplier = resolveAgeMultiplier(underwritingCase);
        // BY RANGE, against the real amount (V9). This used to hand the product a band STRING
        // produced from thresholds hardcoded here -- LOW/MEDIUM/HIGH at two and ten million --
        // while the product author typed a band into a free-text box. A real published product
        // carried '5000000', matched none of the three, resolved to the neutral 1.0, and priced
        // every policy as though the factor did not exist. Nobody was warned: the row was there,
        // the multiplier was there, the console showed it, and it did nothing.
        BigDecimal sumAssuredMultiplier = productApi.resolveSumAssuredMultiplier(
            underwritingCase.getProductVersionId(), underwritingCase.getSumAssuredAmount());

        List<BigDecimal> riskScores = latestScorePerAssessmentType(underwritingCase.getCaseId());

        RiskProfile profile = new RiskProfile(ageMultiplier, sumAssuredMultiplier,
            resolveOccupationClassMultiplier(underwritingCase), riskScores);
        UnderwritingDecision recommendation = rulesEnginePort.evaluate(profile);

        underwritingCase.recordRecommendation(
            recommendation.outcome().name(), recommendation.loadingPercent(), recommendation.reason(),
            // The multiplier the engine actually rated on, not a second resolution of the same
            // two factors here. Issuance prices the policy from this column, so it has to be the
            // number that produced the advice sitting beside it.
            recommendation.ratingMultiplier());
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
     * <p>Neutral 1.0 when the life assured has no recorded date of birth. A CORPORATE or GROUP
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
        // THE LIFE ASSURED's age, not the applicant's. This read the applicant until occupation
        // class and the base rate table arrived, both of which key on the life assured -- so on
        // a parent insuring a child the same contract was being rated on the parent's age and
        // priced on the child's mortality. Whose age it is has to be one answer, and it is the
        // life assured's: they are the life the product is pricing.
        LocalDate dateOfBirth = partyApi.getPartyDetail(lifeAssuredPartyId(underwritingCase)).dateOfBirth();
        if (dateOfBirth == null) {
            return BigDecimal.ONE;
        }
        int age = Period.between(dateOfBirth, LocalDate.now()).getYears();
        return productApi.resolveAgeMultiplier(underwritingCase.getProductVersionId(), age);
    }

    /**
     * Whose life the product is rating.
     *
     * <p>A null {@code lifeAssuredPartyId} means the applicant insures themselves, which is
     * most business — it is not missing data. Every rating input on this path resolves through
     * here so they cannot disagree about whose facts are being priced, which they did once.
     */
    private static UUID lifeAssuredPartyId(UnderwritingCase underwritingCase) {
        return underwritingCase.getLifeAssuredPartyId() != null
            ? underwritingCase.getLifeAssuredPartyId()
            : underwritingCase.getApplicantPartyId();
    }

    /**
     * What this applicant does for a living, priced.
     *
     * <p>Matched by STRING, unlike age and sum assured, and that is correct rather than an
     * inconsistency. An occupation class is a code an actuary writes on a rating table and a
     * registrar picks on a form — both sides are the same vocabulary, chosen by the same
     * organisation. The two factors that had to move to ranges were the ones where the platform
     * INVENTED a string the author never saw: an age band rendered from a date of birth, and
     * LOW/MEDIUM/HIGH hardcoded in Java.
     *
     * <p>Neutral 1.0 when nobody recorded an occupation class, which is a real state — the
     * person record makes every field below the name optional, precisely because a registrar in
     * front of a walk-in may not have the answers. An unclassified applicant is not a rated one.
     */
    private BigDecimal resolveOccupationClassMultiplier(UnderwritingCase underwritingCase) {
        // The LIFE ASSURED, not the applicant: it is their occupation that carries the risk, and
        // on a parent insuring a child the two are different people.
        String occupationClass = partyApi.getPartyDetail(lifeAssuredPartyId(underwritingCase)).occupationClass();
        if (occupationClass == null || occupationClass.isBlank()) {
            return BigDecimal.ONE;
        }
        return productApi.resolveRatingMultiplier(underwritingCase.getProductVersionId(),
            FactorType.OCCUPATION_CLASS, occupationClass);
    }

    /*
     * resolveSumAssuredBand(BigDecimal) is GONE, and its removal is the fix rather than a tidy-up.
     *
     * It returned one of three strings -- "LOW", "MEDIUM", "HIGH" -- on thresholds of 2,000,000
     * and 10,000,000 written here in Java, and the product was then asked for a rating row whose
     * band text equalled that string. So a product's sum assured bands were not the product's to
     * decide: only three spellings ever worked, none of them appeared on the authoring screen,
     * and everything else resolved to the neutral 1.0 with nothing said. A real product published
     * with the band '5000000' priced every policy as though it had no sum assured factor at all.
     *
     * productApi.resolveSumAssuredMultiplier now matches the real amount against the product's
     * own inclusive bounds (product V9), which is what V5 did for AGE after the identical defect.
     */

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

    /**
     * {@inheritDoc}
     *
     * <p>Its own transaction, because the caller's has already rolled back. Policy's issuance
     * listener calls this from a catch block: the transaction that tried to create the policy is
     * gone by then, and writing into it would write into nothing — the same
     * already-committed-transaction trap that made this listener's very first draft find zero
     * policy rows with no exception anywhere.
     *
     * <p>Deliberately does not touch status, outcome or any decision field. The decision was
     * real and stands; what failed was the issuance behind it.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIssuanceFailure(UUID caseId, String reason) {
        UnderwritingCase underwritingCase = findOrThrow(caseId, TenantContext.get());
        underwritingCase.recordIssuanceFailure(reason);
        underwritingCaseRepository.save(underwritingCase);
    }

    private UnderwritingCase findOrThrow(UUID caseId, UUID tenantId) {
        return underwritingCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
            .orElseThrow(() -> new UnderwritingCaseNotFoundException(caseId));
    }

    private UnderwritingCaseView toView(UnderwritingCase c) {
        // Resolved per row. On the queue this is one extra query per case, which is the
        // price of the queue being able to say "this one is a scheme" at all -- and the
        // proposal itself is @JsonIgnore, so a 500-row schedule never reaches a list
        // response. If the queue ever gets slow, the fix is a projection carrying just the
        // boolean, not dropping the distinction.
        GroupProposal groupProposal = groupProposalFor(c.getCaseId(), c.getTenantId());
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
            groupProposal != null, groupProposal,
            c.getRatingMultiplier(),
            c.getIssuanceFailureReason(), c.getIssuanceFailedAt(),
            List.of(),
            // One more query per row, for the same reason and at the same price as the group
            // proposal lookup above.
            c.getCreatedBy(), riskAssessmentRepository.findDistinctAssessors(c.getTenantId(), c.getCaseId()),
            c.getEvidenceForPolicyNumber(), c.getEvidenceForMemberId());
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
            base.groupScheme(), base.groupProposal(),
            base.ratingMultiplier(),
            base.issuanceFailureReason(), base.issuanceFailedAt(),
            nominations, base.openedBy(), base.assessedBy(),
            base.evidenceForPolicyNumber(), base.evidenceForMemberId());
    }
}
