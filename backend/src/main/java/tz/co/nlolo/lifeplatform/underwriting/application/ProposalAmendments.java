package tz.co.nlolo.lifeplatform.underwriting.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalAmendmentApi;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseNotFoundException;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;
import tz.co.nlolo.lifeplatform.underwriting.domain.CaseEvent;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingCase;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.CaseEventRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnderwritingCaseRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** {@link ProposalAmendmentApi}: the return-for-amendment state machine and the history it keeps. */
@Service
public class ProposalAmendments implements ProposalAmendmentApi {

    private final UnderwritingCaseRepository cases;
    private final CaseEventRepository events;
    private final UnderwritingApi underwritingApi;
    private final UnderwritingApiImpl rules;
    private final FuneralApplications funeralApplications;
    private final PartyApi partyApi;
    private final ObjectMapper objectMapper;
    private final ProposalArrivals arrivals;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    public ProposalAmendments(UnderwritingCaseRepository cases, CaseEventRepository events,
                              UnderwritingApi underwritingApi, UnderwritingApiImpl rules,
                              FuneralApplications funeralApplications, PartyApi partyApi, ObjectMapper objectMapper,
                              ProposalArrivals arrivals,
                              org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.cases = cases;
        this.events = events;
        this.underwritingApi = underwritingApi;
        this.rules = rules;
        this.funeralApplications = funeralApplications;
        this.partyApi = partyApi;
        this.objectMapper = objectMapper;
        this.arrivals = arrivals;
        this.jdbc = jdbc;
    }

    /**
     * When each waiting case last arrived: its latest resubmission, else when it was opened. Read from the history
     * rather than kept as a column, so the case table -- built by hand in sixty-odd test fixtures -- is unchanged.
     */
    private static final String ARRIVALS_FROM = """
          FROM underwriting.underwriting_case c
          LEFT JOIN (SELECT case_id, max(created_at) AS last_resubmitted, count(*) AS resubmissions
                       FROM underwriting.case_event WHERE kind = 'RESUBMITTED' GROUP BY case_id) r
                 ON r.case_id = c.case_id
         WHERE c.tenant_id = :tenantId AND c.status IN ('OPEN', 'IN_REVIEW')
           AND (:resubmittedOnly = false OR r.resubmissions > 0)""";

    @Override
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Arrival> arrivals(boolean resubmittedOnly,
                                                                  org.springframework.data.domain.Pageable pageable) {
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("tenantId", TenantContext.get())
            .addValue("resubmittedOnly", resubmittedOnly)
            .addValue("limit", pageable.getPageSize())
            .addValue("offset", pageable.getOffset());
        Long total = jdbc.queryForObject("SELECT count(*) " + ARRIVALS_FROM, params, Long.class);
        List<Arrival> rows = jdbc.query("""
            SELECT c.case_id, COALESCE(r.last_resubmitted, c.created_at) AS last_submitted,
                   COALESCE(r.resubmissions, 0) AS resubmissions, c.applicant_party_id
            """ + ARRIVALS_FROM + """

             ORDER BY last_submitted DESC, c.case_id
             LIMIT :limit OFFSET :offset""", params, (rs, i) -> {
                UUID caseId = rs.getObject("case_id", UUID.class);
                int resubmissions = rs.getInt("resubmissions");
                return new Arrival(underwritingApi.getCase(caseId),
                    rs.getTimestamp("last_submitted").toInstant(), resubmissions + 1, resubmissions > 0,
                    arrivals.introducedBy(rs.getObject("applicant_party_id", UUID.class)));
            });
        return new org.springframework.data.domain.PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    @Override
    @Transactional
    public UnderwritingCaseView returnForAmendment(UUID caseId, List<ReturnReason> reasons, String note,
                                                   String returnedBy) {
        UnderwritingCase c = load(caseId);
        String status = c.getStatus();
        if (!UnderwritingCaseStatus.OPEN.name().equals(status) && !UnderwritingCaseStatus.IN_REVIEW.name().equals(status)) {
            throw new UnderwritingValidationException("Only an undecided proposal can be returned for amendment; this one is "
                + status);
        }
        if (c.getEvidenceForPolicyNumber() != null) {
            throw new UnderwritingValidationException("A scheme member's evidence case has no agent to return it to");
        }
        if (reasons == null || reasons.isEmpty()) {
            throw new UnderwritingValidationException("A return names at least one reason: what is wrong and what to change");
        }
        for (ReturnReason r : reasons) {
            if (r == null || r.category() == null || blank(r.problem()) || blank(r.change())) {
                throw new UnderwritingValidationException("Every reason needs its category, what is wrong and what to change");
            }
        }
        c.returnForAmendment();
        cases.save(c);
        events.save(new CaseEvent(c.getTenantId(), caseId, CaseEvent.Kind.RETURNED, round(c), returnedBy,
            json(reasons.stream().map(r -> new ReturnReason(r.category(), r.problem().trim(), r.change().trim())).toList()),
            null, null, trimmed(note)));
        return underwritingApi.getCase(caseId);
    }

    @Override
    @Transactional
    public UnderwritingCaseView amendTerms(UUID caseId, TermsAmendment a, String amendedBy) {
        UnderwritingCase c = load(caseId);
        requireAmendable(c);
        if (a.sumAssured() == null || a.sumAssured().signum() <= 0) {
            throw new UnderwritingValidationException("A proposal needs a sum assured or amount above zero");
        }
        // Said in words here rather than left to the table's CHECKs, which answered a typo with a 500.
        if (a.requestedTermMonths() != null && a.requestedTermMonths() <= 0) {
            throw new UnderwritingValidationException("A term is a whole number of months above zero");
        }
        if (a.premiumPayingTermMonths() != null && a.premiumPayingTermMonths() <= 0) {
            throw new UnderwritingValidationException("A premium-paying term is a whole number of months above zero");
        }
        if (a.premiumPayingTermMonths() != null && a.requestedTermMonths() != null
                && a.premiumPayingTermMonths() > a.requestedTermMonths()) {
            throw new UnderwritingValidationException("Premiums cannot be paid for longer than the policy runs: "
                + a.premiumPayingTermMonths() + " months against a term of " + a.requestedTermMonths());
        }
        if (a.premiumFrequency() != null && !java.util.Set.of("MONTHLY", "QUARTERLY", "ANNUALLY", "SINGLE")
                .contains(a.premiumFrequency())) {
            throw new UnderwritingValidationException("A premium is paid MONTHLY, QUARTERLY, ANNUALLY or SINGLE, not "
                + a.premiumFrequency());
        }
        if (funeralApplications.isFuneral(c) && c.getSumAssuredAmount() != null
                && a.sumAssured().compareTo(c.getSumAssuredAmount()) != 0) {
            throw new UnderwritingValidationException("A funeral plan's sum assured is the main member's benefit on its plan:"
                + " change the plan or the family, not the amount");
        }
        // The rules the proposal was opened under, applied again to what it now asks for.
        ProposalDetails asked = new ProposalDetails(null, null, null, a.proposedCommencementDate(), a.requestedTermMonths(),
            a.premiumPayingTermMonths(), a.premiumFrequency(), List.of(), a.proposedRatePercent());
        rules.refuseAnUnissuableDeposit(c.getProductVersionId(), a.sumAssured(), asked);
        rules.refuseAnUnissuableTermAccount(c.getProductVersionId(), asked);
        rules.rejectTermOutsideProductBounds(c.getProductVersionId(), a.requestedTermMonths());
        if ("SINGLE".equals(a.premiumFrequency()) && a.premiumPayingTermMonths() != null && a.premiumPayingTermMonths() != 1) {
            throw new UnderwritingValidationException("A SINGLE premium is charged once, so its premium-paying term"
                + " must be 1 month (or absent), not " + a.premiumPayingTermMonths());
        }

        List<FieldChange> changes = new ArrayList<>();
        change(changes, "Sum assured", c.getSumAssuredAmount(), a.sumAssured());
        change(changes, "Term (months)", c.getRequestedTermMonths(), a.requestedTermMonths());
        change(changes, "Premium-paying term (months)", c.getPremiumPayingTermMonths(), a.premiumPayingTermMonths());
        change(changes, "Premium frequency", c.getPremiumFrequency(), a.premiumFrequency());
        change(changes, "Cover from", c.getProposedCommencementDate(), a.proposedCommencementDate());
        change(changes, "Rate (%)", c.getProposedRatePercent(), a.proposedRatePercent());
        if (changes.isEmpty()) {
            return underwritingApi.getCase(caseId);
        }
        c.amendTerms(a.sumAssured(), a.requestedTermMonths(), a.premiumPayingTermMonths(), a.premiumFrequency(),
            a.proposedCommencementDate(), a.proposedRatePercent());
        cases.save(c);
        events.save(new CaseEvent(c.getTenantId(), caseId, CaseEvent.Kind.AMENDED, round(c), amendedBy, null,
            json(changes), null, null));
        return underwritingApi.getCase(caseId);
    }

    @Override
    @Transactional
    public PartyDetailView amendApplicant(UUID caseId, IndividualRegistration corrected, String amendedBy) {
        UnderwritingCase c = load(caseId);
        requireAmendable(c);
        PartyDetailView before = partyApi.getPartyDetail(c.getApplicantPartyId());

        List<FieldChange> changes = new ArrayList<>();
        change(changes, "Full name", before.displayName(), corrected.fullName());
        change(changes, "Date of birth", before.dateOfBirth(), corrected.dateOfBirth());
        change(changes, "Phone", before.phoneNumber(), corrected.phoneNumber());
        change(changes, "Email", before.email(), corrected.email());
        change(changes, "Sex", before.sex(), corrected.sex());
        change(changes, "Smoker status", before.smokerStatus(), corrected.smokerStatus());
        change(changes, "Occupation", before.occupation(), corrected.occupation());
        change(changes, "Occupation class", before.occupationClass(), corrected.occupationClass());
        change(changes, "Employer", before.employerName(), corrected.employerName());
        change(changes, "Nationality", before.nationality(), corrected.nationality());
        change(changes, "Identity document", before.identityDocument(), corrected.identityDocument());
        change(changes, "Address", before.address(), corrected.address());
        if (changes.isEmpty()) {
            return before;
        }
        PartyDetailView after = partyApi.amendIndividual(c.getApplicantPartyId(), corrected, amendedBy);

        // A verified identity was verified for the old name and date of birth (user, 2026-10-20): checked again.
        boolean identityChanged = !Objects.equals(before.displayName(), corrected.fullName())
            || !Objects.equals(before.dateOfBirth(), corrected.dateOfBirth());
        if (identityChanged && before.kycStatus() == KycStatus.VERIFIED) {
            partyApi.submitKycEvidence(c.getApplicantPartyId(), KycStatus.PENDING,
                "identity corrected on proposal " + c.getProposalNumber(), amendedBy);
            changes.add(new FieldChange("KYC status", KycStatus.VERIFIED.name(), KycStatus.PENDING.name()));
            after = partyApi.getPartyDetail(c.getApplicantPartyId());
        }
        events.save(new CaseEvent(c.getTenantId(), caseId, CaseEvent.Kind.AMENDED, round(c), amendedBy, null,
            json(changes), null, null));
        return after;
    }

    @Override
    @Transactional
    public UnderwritingCaseView resubmit(UUID caseId, String note, String resubmittedBy) {
        UnderwritingCase c = load(caseId);
        requireAmendable(c);
        // A postponement is overwritten by the next decision: what it said is kept with the resubmission answering it.
        PreviousDecision previous = UnderwritingCaseStatus.DECIDED.name().equals(c.getStatus())
            ? new PreviousDecision(DecisionOutcome.valueOf(c.getDecisionOutcome()), c.getDecisionDeclineReason(),
                c.getDecisionDecidedAt())
            : null;
        int next = round(c) + 1;
        c.reopenForResubmission();
        cases.save(c);
        events.save(new CaseEvent(c.getTenantId(), caseId, CaseEvent.Kind.RESUBMITTED, next, resubmittedBy, null, null,
            previous == null ? null : json(previous), trimmed(note)));
        // Back in the queue: the underwriting team is told, after the commit (the alert email).
        arrivals.announce(c, next);
        return underwritingApi.getCase(caseId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseEventView> history(UUID caseId) {
        load(caseId);
        return events.findByTenantIdAndCaseIdOrderByCreatedAtAscEventIdAsc(TenantContext.get(), caseId).stream()
            .map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NeedsAction> needsAction(Set<UUID> applicantPartyIds, Instant declinedSince) {
        if (applicantPartyIds == null || applicantPartyIds.isEmpty()) return List.of();
        UUID tenantId = TenantContext.get();
        List<NeedsAction> out = new ArrayList<>();
        List<UnderwritingCase> found = new ArrayList<>(cases.findNeedingAgentAction(tenantId, applicantPartyIds));
        found.addAll(cases.findRecentDeclines(tenantId, applicantPartyIds, declinedSince,
            org.springframework.data.domain.PageRequest.of(0, DECLINES_SHOWN)));
        for (UnderwritingCase c : found) {
            if (UnderwritingCaseStatus.RETURNED.name().equals(c.getStatus())) {
                CaseEventView latest = latestReturn(c);
                out.add(new NeedsAction(underwritingApi.getCase(c.getCaseId()),
                    latest == null ? List.of() : latest.reasons(), latest == null ? null : latest.note(), true));
            } else if (DecisionOutcome.POSTPONED.name().equals(c.getDecisionOutcome())) {
                out.add(new NeedsAction(underwritingApi.getCase(c.getCaseId()), List.of(), c.getDecisionDeclineReason(), true));
            } else if (DecisionOutcome.DECLINED.name().equals(c.getDecisionOutcome()) && c.getDecisionDecidedAt() != null
                    && !c.getDecisionDecidedAt().isBefore(declinedSince)) {
                out.add(new NeedsAction(underwritingApi.getCase(c.getCaseId()), List.of(), c.getDecisionDeclineReason(), false));
            }
        }
        // What needs doing first -- returned and postponed -- then the declines to read; newest first within each. A
        // returned proposal has no decision date, so it would otherwise sink beneath every decline.
        out.sort(Comparator.comparing(NeedsAction::actionable).reversed()
            .thenComparing((NeedsAction n) -> n.caseView().decisionDecidedAt() != null
                ? n.caseView().decisionDecidedAt() : Instant.EPOCH, Comparator.reverseOrder()));
        return out;
    }

    /** How many recent declines the list carries: enough to tell the customers, never a register of every one. */
    static final int DECLINES_SHOWN = 20;

    private CaseEventView latestReturn(UnderwritingCase c) {
        List<CaseEvent> all = events.findByTenantIdAndCaseIdOrderByCreatedAtAscEventIdAsc(c.getTenantId(), c.getCaseId());
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).getKind() == CaseEvent.Kind.RETURNED) return view(all.get(i));
        }
        return null;
    }

    /** RETURNED, or decided POSTPONED. A decline is final (user, 2026-10-20); anything else is the underwriter's. */
    private static void requireAmendable(UnderwritingCase c) {
        boolean returned = UnderwritingCaseStatus.RETURNED.name().equals(c.getStatus());
        boolean postponed = UnderwritingCaseStatus.DECIDED.name().equals(c.getStatus())
            && DecisionOutcome.POSTPONED.name().equals(c.getDecisionOutcome());
        if (!returned && !postponed) {
            boolean declined = DecisionOutcome.DECLINED.name().equals(c.getDecisionOutcome());
            throw new UnderwritingValidationException(declined
                ? "A declined proposal is final: start a new quotation to propose again"
                : "Only a proposal returned for amendment or postponed can be amended or resubmitted; this one is "
                    + c.getStatus());
        }
    }

    /** The submission the case is in: the first, plus one for each resubmission. */
    private int round(UnderwritingCase c) {
        return 1 + (int) events.findByTenantIdAndCaseIdOrderByCreatedAtAscEventIdAsc(c.getTenantId(), c.getCaseId())
            .stream().filter(e -> e.getKind() == CaseEvent.Kind.RESUBMITTED).count();
    }

    private UnderwritingCase load(UUID caseId) {
        return cases.findByCaseIdAndTenantId(caseId, TenantContext.get())
            .orElseThrow(() -> new UnderwritingCaseNotFoundException(caseId));
    }

    private static void change(List<FieldChange> changes, String field, Object before, Object after) {
        String b = text(before);
        String a = text(after);
        if (!Objects.equals(b, a)) changes.add(new FieldChange(field, b, a));
    }

    /** Money compared by value, not scale: 1000000 and 1000000.00 are the same sum assured. */
    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
        // Read by a person in the history: the document and the address as they would be written down.
        if (value instanceof tz.co.nlolo.lifeplatform.party.api.IdentityDocument doc) {
            return doc.type() == null && blank(doc.number()) ? null
                : (doc.type() == null ? "" : doc.type().name() + " ") + (doc.number() == null ? "" : doc.number());
        }
        if (value instanceof tz.co.nlolo.lifeplatform.party.api.Address a) {
            String joined = java.util.stream.Stream.of(a.line(), a.ward(), a.district(), a.region(), a.postalCode())
                .filter(p -> !blank(p)).collect(java.util.stream.Collectors.joining(", "));
            return joined.isEmpty() ? null : joined;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimmed(String s) {
        return blank(s) ? null : s.trim();
    }

    private CaseEventView view(CaseEvent e) {
        return new CaseEventView(e.getEventId(), EventKind.valueOf(e.getKind().name()), e.getRound(), e.getActorSubject(),
            e.getReasons() == null ? List.of() : read(e.getReasons(), new TypeReference<List<ReturnReason>>() {}),
            e.getChanges() == null ? List.of() : read(e.getChanges(), new TypeReference<List<FieldChange>>() {}),
            e.getPreviousDecision() == null ? null : read(e.getPreviousDecision(), new TypeReference<PreviousDecision>() {}),
            e.getNote(), e.getCreatedAt());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
