package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.domain.*;
import tz.co.nlolo.lifeplatform.policy.infrastructure.*;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitDefinition;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PolicyApiImpl implements PolicyApi {

    private static final Logger log = LoggerFactory.getLogger(PolicyApiImpl.class);

    private final PolicyRepository policyRepository;
    private final PolicyAccountRepository policyAccountRepository;
    private final EndorsementRepository endorsementRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final CoverageRepository coverageRepository;
    private final LoanValueReservationRepository loanValueReservationRepository;
    private final GroupSchemeRepository groupSchemeRepository;
    private final GroupSchemeGradeRepository groupSchemeGradeRepository;
    private final PolicyMemberRepository policyMemberRepository;
    private final PolicyMemberBenefitRepository policyMemberBenefitRepository;
    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final ReferenceDataApi referenceDataApi;
    private final DistributionApi distributionApi;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public PolicyApiImpl(PolicyRepository policyRepository, PolicyAccountRepository policyAccountRepository,
                          EndorsementRepository endorsementRepository, BeneficiaryRepository beneficiaryRepository,
                          CoverageRepository coverageRepository, LoanValueReservationRepository loanValueReservationRepository,
                          GroupSchemeRepository groupSchemeRepository, GroupSchemeGradeRepository groupSchemeGradeRepository,
                          PolicyMemberRepository policyMemberRepository, PolicyMemberBenefitRepository policyMemberBenefitRepository,
                          PartyApi partyApi, ProductApi productApi, ReferenceDataApi referenceDataApi,
                          DistributionApi distributionApi,
                          ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.policyAccountRepository = policyAccountRepository;
        this.endorsementRepository = endorsementRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.coverageRepository = coverageRepository;
        this.loanValueReservationRepository = loanValueReservationRepository;
        this.groupSchemeRepository = groupSchemeRepository;
        this.groupSchemeGradeRepository = groupSchemeGradeRepository;
        this.policyMemberRepository = policyMemberRepository;
        this.policyMemberBenefitRepository = policyMemberBenefitRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.referenceDataApi = referenceDataApi;
        this.distributionApi = distributionApi;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * WHO EARNS THE COMMISSION ON THIS POLICY.
     *
     * <p><b>The agent who registered the client, always.</b> That is a business rule, not a
     * default: the registering agent's attribution BINDS, and a value supplied on the request
     * does not override it. An overridable attribution is a negotiable commission, and the
     * negotiation would happen on a form months after the agent did the work.
     *
     * <p>What this closes: commission accrues in distribution off
     * {@code PolicyActivated.agentOfRecordId}, which until now was only ever whatever the form
     * that opened the case happened to carry — null for a direct sale. "Who registered this
     * client" was recorded separately, as a Keycloak subject in {@code party.created_by}, which
     * nothing could resolve to an agent. So an agent could sign a customer up and earn nothing on
     * their policies unless somebody separately named them on every case.
     *
     * <p>This is THE ONLY MODULE that can close it. Party may not depend on distribution and so
     * cannot name an agent; underwriting may not either, so a case cannot resolve one when it is
     * opened. Policy may depend on both — and it is also what emits the event commission listens
     * to, so binding here covers automatic issuance, manual issue and group schemes at once
     * without three request DTOs having to agree.
     *
     * <p>Falls back to the supplied value when the client has no registering agent: staff
     * registered them, they registered themselves, or they predate the column. Attributing a sale
     * by hand is still a real thing staff do, and null still means direct.
     *
     * @param suppliedAgentOfRecordId what the caller asked for, honoured only as a fallback.
     */
    private UUID agentOfRecordFor(UUID policyholderPartyId, UUID suppliedAgentOfRecordId) {
        UUID registeredBy = partyApi.getPartyDetail(policyholderPartyId).registeredByPartyId();
        if (registeredBy == null) {
            return requireRealAgent(suppliedAgentOfRecordId);
        }
        return distributionApi.agentIdForParty(registeredBy)
            // Registered by somebody who is not an agent in this tenant -- a staff member whose
            // token happened to carry a party_id, most likely. Not an error, and not a reason to
            // drop an attribution the caller did make.
            .orElseGet(() -> requireRealAgent(suppliedAgentOfRecordId));
    }

    /**
     * A hand-typed agent of record must name a real agent, or the issuance is refused.
     *
     * <p>Until this check existed the id was stored opaquely and never verified, so a mistyped or
     * stale uuid produced a policy attributed to nobody: the accrual listener resolved no agent,
     * logged it server-side, and accrued nothing. The operator saw a successfully issued policy
     * and had, without being told, made the sale direct. Six such policies exist in this
     * platform's own dev data, all pointing at one phantom id.
     *
     * <p>Null is untouched and still means a direct sale -- the honest, deliberate way to issue a
     * policy nobody earns on.
     *
     * <p>A BACKSTOP, not the only check. {@code UnderwritingApiImpl.openCase} refuses an unknown
     * agent when the case is opened, which is where whoever supplied it can still fix it — a
     * case's agent of record cannot be corrected afterwards. This catches the manual-issue path,
     * where the value arrives on the issue request itself and there is no earlier boundary.
     */
    private UUID requireRealAgent(UUID suppliedAgentOfRecordId) {
        if (suppliedAgentOfRecordId == null) {
            return null;
        }
        // Tenant-scoped, so an agent belonging to another tenant is correctly "not an agent".
        if (distributionApi.getAgentIfPresent(suppliedAgentOfRecordId).isEmpty()) {
            throw new UnknownAgentOfRecordException(suppliedAgentOfRecordId);
        }
        return suppliedAgentOfRecordId;
    }

    @Override
    @Transactional
    public PolicyView issuePolicy(UUID underwritingCaseId, IssueRequest request, String issuedBy) {
        UUID tenantId = TenantContext.get();

        // Before anything is written, and before the party and product lookups, so a retry of
        // an issuance that already succeeded is answered by the fact rather than by whichever
        // validation happens to fail first.
        //
        // ux_policy_underwriting_case is the real guarantee -- a constraint cannot be raced --
        // but it surfaces as a 500 carrying a Postgres string, and the operator who trips this
        // needs the number of the policy that already exists so they can go and look at it.
        if (underwritingCaseId != null) {
            policyRepository.findByTenantIdAndUnderwritingCaseId(tenantId, underwritingCaseId)
                .ifPresent(existing -> {
                    throw new PolicyAlreadyIssuedForCaseException(underwritingCaseId, existing.getPolicyNumber());
                });
        }

        partyApi.getParty(request.policyholderPartyId()); // existence check -- PartyNotFoundException propagates as-is
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(request.productId(), LocalDate.now());

        // Placeholder generation scheme (flagged): policy.policy's own column comment describes
        // a "tenant/product/year/sequence, human-meaningful for USSD/call-center lookup"
        // business key -- no sequence generator or product-code lookup is wired here. This is
        // pattern-valid (^[A-Z0-9-]{6,20}$) and unique enough for M3; a later milestone can
        // replace the generation strategy without changing this method's signature.
        String policyNumber = "POL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        List<Beneficiary> beneficiaries = validateAndBuildBeneficiaries(tenantId, policyNumber, request.beneficiaries());

        Policy policy = new Policy(policyNumber, tenantId, request.policyholderPartyId(), request.productId(), request.productVersionId(),
            snapshot.category().name(), agentOfRecordFor(request.policyholderPartyId(), request.agentOfRecordId()),
            request.sumAssuredAmount(), request.sumAssuredCurrency(),
            request.premiumAmount(), request.premiumCurrency(), request.premiumFrequency(), underwritingCaseId, issuedBy);
        // Before activate, so an invalid term is refused before the policy is put in
        // force rather than after. All three may be null: a product that does not term
        // is not the same as a term nobody recorded.
        policy.applyTerm(request.commencementDate(), request.policyTermMonths(),
            request.premiumPayingTermMonths());
        // Self-insured resolves to the policyholder here, so the column always answers
        // "whose life is this" rather than leaving every reader to infer it from a null.
        policy.recordLifeAssured(request.resolveLifeAssured());
        // Always, and before the conditional activation below: an offer has an issue date too.
        // See Policy.recordIssuedOn for the readers that would otherwise get a null.
        policy.recordIssuedOn(LocalDate.now());

        // An accepted decision produces an OFFER, not cover. The policy exists so the customer
        // has something to pay against -- billing raises its first invoice off PolicyIssued --
        // and the first cleared premium activates it.
        //
        // A manual issuance whose basis already carries cover skips the wait. See IssuanceBasis:
        // the three that do are the three where the contract is in force somewhere else already.
        boolean startsCoverNow = request.issuanceBasis() != null
            && request.issuanceBasis().startsCoverImmediately();
        if (startsCoverNow) {
            policy.activate();
        }
        policyRepository.save(policy);

        policyAccountRepository.save(new PolicyAccount(policyNumber, tenantId, BigDecimal.ZERO, request.sumAssuredCurrency()));

        // One Coverage per authored benefit, at the amount that benefit pays. This was a single
        // hardcoded DEATH row whatever the product declared -- the comment here used to explain
        // that a per-benefit row was not buildable because ProductApi exposed no getter for the
        // schedule. resolveBenefitSchedule is that getter.
        List<BenefitDefinition> benefits = productApi.resolveBenefitSchedule(request.productVersionId());
        if (benefits.isEmpty()) {
            // GRANDFATHERING, and it expires on its own. 143 versions predate the rule that a
            // version must cover at least one benefit; publishVersion now refuses an empty
            // schedule, so this population can only SHRINK -- which is what makes this a
            // migration path rather than a permanent default.
            //
            // Nothing is invented here: these policies keep exactly the cover they have today,
            // the policy's own sum assured, which is the number claimableCover already returned
            // for them before it knew about benefit types at all.
            coverageRepository.save(new Coverage(tenantId, policyNumber, BenefitType.DEATH.name(),
                request.sumAssuredAmount(), request.sumAssuredCurrency()));
        } else {
            for (BenefitDefinition benefit : benefits) {
                coverageRepository.save(new Coverage(tenantId, policyNumber, benefit.benefitType().name(),
                    benefit.amountFor(request.sumAssuredAmount()), request.sumAssuredCurrency()));
            }
        }

        beneficiaryRepository.saveAll(beneficiaries);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", request.policyholderPartyId());
        payload.put("productId", request.productId());
        payload.put("productVersionId", request.productVersionId());
        payload.put("sumAssured", Map.of("amount", request.sumAssuredAmount().toPlainString(), "currencyCode", request.sumAssuredCurrency()));
        payload.put("issueDate", policy.getIssueDate().toString());
        payload.put("premium", Map.of("amount", request.premiumAmount().toPlainString(), "currencyCode", request.premiumCurrency()));
        payload.put("premiumFrequency", request.premiumFrequency());
        // THE RESOLVED AGENT, off the policy -- not the raw request value.
        //
        // These two had drifted apart: the policy row stored the agent bound by
        // agentOfRecordFor (the client's registering agent, where there is one) while this event
        // still announced whatever the caller typed. PolicyActivated already carried the resolved
        // one, and commission accrues off PolicyActivated, so nothing was mispaid -- but the
        // platform was publishing two different answers to "who sold this policy" and the wrong
        // one was the earlier, more obvious event to consume. Neither current subscriber reads
        // this key; the next one should not have to know which event to trust.
        //
        // Still nullable: null means a direct sale. See Global Constraints.
        payload.put("agentOfRecordId", policy.getAgentOfRecordId());
        // PROPOSED for ordinary new business, ACTIVE for an issuance basis that already carries
        // cover. Carried because a consumer cannot ask: communication must tell an offer's
        // customer to pay by a date, and must NOT tell that to somebody whose migrated policy is
        // already in force -- and it may not depend on policy to find out which it is looking at.
        payload.put("status", policy.getStatus());
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyIssued", tenantId, payload));

        // Both events together for an immediate-cover issuance, so every downstream consumer
        // behaves exactly as it did before this change.
        if (startsCoverNow) {
            publishPolicyActivated(policyNumber, tenantId, policy);
        }

        return toView(policy);
    }

    @Override
    @Transactional
    public void activateOnFirstPremium(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Silent, not an exception. Billing re-delivering a PremiumCollected, an ordinary second
        // month, and a MIGRATION policy that was never an offer all land here legitimately, and
        // none of them is a fault worth failing the caller's transaction over.
        if (!PolicyStatus.PROPOSED.name().equals(policy.getStatus())) {
            return;
        }
        policy.activate();
        policyRepository.save(policy);
        publishPolicyActivated(policyNumber, tenantId, policy);
    }

    @Override
    @Transactional
    public void expireOffer(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Silent on anything that is not an outstanding offer. A policy that was paid for
        // between the sweep selecting it and this call reaching it lands here legitimately, and
        // racing the customer's own payment is not a fault to fail a caller over -- it is the
        // customer winning, which is the outcome everybody wanted.
        if (!PolicyStatus.PROPOSED.name().equals(policy.getStatus())) {
            return;
        }
        policy.markNotTakenUp();
        policyRepository.save(policy);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        // Carried because the consumer that needs it most cannot look it up: communication may
        // not depend on policy, so an event that named only the policy would leave it with
        // nobody to tell.
        payload.put("policyholderPartyId", policy.getPolicyholderPartyId());
        payload.put("productId", policy.getProductId());
        payload.put("expiredAt", LocalDate.now().toString());
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyNotTakenUp", tenantId, payload));
    }

    /**
     * "On risk, premium received." The event commission, cession and the regulatory return key
     * off — as distinct from {@code PolicyIssued}, which now only means the contract record
     * exists.
     *
     * <p>The keys are not a fresh design. They are the union of what distribution's, reinsurance's
     * and regreporting's {@code handlePolicyIssued} actually read, checked against each handler
     * rather than assumed from the producer: {@code policyNumber}, {@code productId}, {@code
     * issueDate}, {@code premium}, {@code sumAssured} and {@code agentOfRecordId}. {@code
     * issueDate} in particular is read by all three — it dates the treaty selection, the
     * surrender-charge band and the clawback window — and is the issue date of the contract, not
     * the activation date, which is why both appear here as separate keys.
     */
    private void publishPolicyActivated(String policyNumber, UUID tenantId, Policy policy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", policy.getPolicyholderPartyId());
        payload.put("productId", policy.getProductId());
        payload.put("productVersionId", policy.getProductVersionId());
        payload.put("sumAssured", Map.of("amount", policy.getSumAssuredAmount().toPlainString(),
                                          "currencyCode", policy.getSumAssuredCurrency()));
        payload.put("premium", Map.of("amount", policy.getPremiumAmount().toPlainString(),
                                       "currencyCode", policy.getPremiumCurrency()));
        payload.put("premiumFrequency", policy.getPremiumFrequency());
        payload.put("issueDate", policy.getIssueDate().toString());
        payload.put("agentOfRecordId", policy.getAgentOfRecordId()); // nullable -- a direct sale
        payload.put("activatedAt", LocalDate.now().toString());
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyActivated", tenantId, payload));
    }

    @Override
    @Transactional
    public PolicyView applyEndorsement(String policyNumber, EndorsementInput request, String appliedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        if (!policy.isInForce()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be in force to apply an endorsement (current: " + policy.getStatus() + ")");
        }
        Endorsement endorsement = new Endorsement(tenantId, policyNumber, request.endorsementType(), request.effectiveDate(), request.changes(), appliedBy);
        endorsementRepository.save(endorsement);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyEndorsed", tenantId,
            Map.of("policyNumber", policyNumber, "endorsementType", request.endorsementType(), "effectiveDate", request.effectiveDate().toString())));
        return toView(policy);
    }

    @Override
    public List<BeneficiaryOfView> beneficiaryOf(UUID partyId) {
        return beneficiaryRepository.findActiveBeneficiaryOf(TenantContext.get(), partyId);
    }

    @Override
    @Transactional
    public void replaceBeneficiaries(String policyNumber, List<BeneficiaryInput> beneficiaries, String changedBy) {
        UUID tenantId = TenantContext.get();
        findPolicyOrThrow(policyNumber, tenantId);
        List<Beneficiary> newBeneficiaries = validateAndBuildBeneficiaries(tenantId, policyNumber, beneficiaries);

        List<Beneficiary> existing = beneficiaryRepository.findByPolicyNumberAndActiveTrue(policyNumber);
        existing.forEach(Beneficiary::deactivate);
        beneficiaryRepository.saveAll(existing);
        beneficiaryRepository.saveAll(newBeneficiaries);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.BeneficiaryChanged", tenantId,
            Map.of("policyNumber", policyNumber, "changedAt", Instant.now().toString())));
    }

    /**
     * Deliberately NOT @Transactional-write and deliberately event-free: this is the read
     * policyloan's forced-lapse sweep-follow-up calls, and a health check that emitted an event
     * per loan per day would drown every consumer of policy's event stream.
     *
     * <p>findById, not lockByPolicyNumber: the caller compares this against a loan balance and
     * may then lapse the policy, but taking a row lock on policy_account here would hold it
     * across policyloan's own writes for no benefit -- the decision is advisory either way, since
     * cash value can move a millisecond later. lapsePolicy re-reads and guards its own state.
     */
    @Override
    public CashValueView getCashValue(String policyNumber) {
        UUID tenantId = TenantContext.get();
        // findPolicyOrThrow first, so a policy in another tenant answers 404 identically to one
        // that does not exist (the anti-enumeration property every read on this API preserves)
        // rather than leaking existence through a different error on the account lookup.
        findPolicyOrThrow(policyNumber, tenantId);
        PolicyAccount account = policyAccountRepository.findById(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        return new CashValueView(policyNumber, account.getCashValueAmount(), account.getCashValueCurrency(),
            account.getLoanEncumbranceAmount());
    }

    @Override
    public SurrenderQuoteView quoteSurrenderValue(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        PolicyAccount account = policyAccountRepository.findById(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(policy.getProductId(), LocalDate.now());

        BigDecimal chargePercent = resolveSurrenderChargePercent(snapshot.surrenderChargeScheduleJson(), policy.getIssueDate());
        BigDecimal charge = account.getCashValueAmount().multiply(chargePercent).divide(new BigDecimal("100"));
        BigDecimal quotedValue = account.getCashValueAmount().subtract(charge);
        Instant quotedAt = Instant.now();

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.SurrenderValueCalculated", tenantId,
            Map.of("policyNumber", policyNumber,
                   "quotedValue", Map.of("amount", quotedValue.toPlainString(), "currencyCode", account.getCashValueCurrency()),
                   "quotedAt", quotedAt.toString())));

        return new SurrenderQuoteView(policyNumber, quotedValue, account.getCashValueCurrency(), quotedAt);
    }

    /**
     * Duration-band -> charge% shape (Global Constraints -- a plan-level decision pending
     * Actuarial confirmation, not a confirmed contractual schedule): a JSON array of
     * {"minMonths": int, "maxMonths": int-or-absent, "chargePercent": number} objects,
     * minMonths inclusive, maxMonths exclusive (absent/null = unbounded). A missing, blank, or
     * unparseable schedule means ZERO charge -- this must never throw out to the caller.
     *
     * <p>Package-private (not private) solely so PolicyApiImplSurrenderChargeTest can exercise
     * this money-affecting band-parsing/percentage logic directly, without a Spring context --
     * mirroring underwriting.SimpleRulesEngineTest's approach to unit-testing pure decision logic.
     */
    BigDecimal resolveSurrenderChargePercent(String scheduleJson, LocalDate issueDate) {
        if (scheduleJson == null || scheduleJson.isBlank() || issueDate == null) {
            return BigDecimal.ZERO;
        }
        try {
            long monthsInForce = Period.between(issueDate, LocalDate.now()).toTotalMonths();
            JsonNode bands = objectMapper.readTree(scheduleJson);
            for (JsonNode band : bands) {
                long minMonths = band.path("minMonths").asLong(0);
                long maxMonths = band.hasNonNull("maxMonths") ? band.path("maxMonths").asLong() : Long.MAX_VALUE;
                if (monthsInForce >= minMonths && monthsInForce < maxMonths) {
                    return new BigDecimal(band.path("chargePercent").asText("0"));
                }
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            log.warn("Unparseable surrender_charge_schedule for a policy issued {} -- treating as zero charge", issueDate, e);
            return BigDecimal.ZERO;
        }
    }

    @Override
    public PolicyView getPolicy(String policyNumber) {
        return toView(findPolicyOrThrow(policyNumber, TenantContext.get()));
    }

    @Override
    public Page<PolicyView> searchPolicies(UUID policyholderPartyId, UUID relatedPartyId, PolicyStatus status,
                                            Set<UUID> agentOfRecordIds, String q, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<Policy> page;
        boolean hasQ = q != null && !q.isBlank();
        // The three no-agent-filter branches stay on the original derived-query methods (unchanged
        // shape, unchanged behaviour) rather than routing everything through the new three-way
        // `search` query for the common (non-agent) case. Critically, the guard here is `!= null`,
        // NOT `!= null && !isEmpty()`: an EMPTY (but non-null) set is a real, security-relevant
        // case -- an agents-realm caller whose resolved team came back empty (its party is not
        // actually an agent in this tenant, an edge case DistributionApi.resolveAgentTeam allows
        // for) must see ZERO policies, not silently fall through to the unfiltered "no agent
        // filter" branches and see the whole tenant. `PolicyRepository.search`'s JPQL handles an
        // empty `IN (...)` collection correctly (matches nothing), so routing there is sufficient.
        //
        // A present q ALSO routes through the wider `search` query, same reasoning: only the
        // truly-unfiltered common case stays on the fast derived-query methods.
        // relatedPartyId joins agentOfRecordIds and q as a reason to route through the wider
        // `search` query: the four derived-query branches below cannot express its three-way
        // OR at all, so a request carrying it MUST NOT fall through to them -- doing so would
        // silently ignore the filter and return the whole tenant's policies to a claims desk
        // asking about one person.
        if (agentOfRecordIds != null || hasQ || relatedPartyId != null) {
            page = policyRepository.search(tenantId, policyholderPartyId, relatedPartyId,
                status != null ? status.name() : null,
                agentOfRecordIds, hasQ ? q.trim() : null, pageable);
        } else if (policyholderPartyId != null && status != null) {
            page = policyRepository.findByTenantIdAndPolicyholderPartyIdAndStatus(tenantId, policyholderPartyId, status.name(), pageable);
        } else if (policyholderPartyId != null) {
            page = policyRepository.findByTenantIdAndPolicyholderPartyId(tenantId, policyholderPartyId, pageable);
        } else if (status != null) {
            page = policyRepository.findByTenantIdAndStatus(tenantId, status.name(), pageable);
        } else {
            page = policyRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(this::toView);
    }

    @Override
    public Set<String> policyNumbersForAgentTeam(UUID callerPartyId) {
        List<UUID> team = distributionApi.resolveAgentTeam(callerPartyId);
        if (team.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(policyRepository.findPolicyNumbersByTenantIdAndAgentOfRecordIdIn(TenantContext.get(), team));
    }

    @Override
    public CoverageStatusView getCoverageStatus(String policyNumber, LocalDate asOf) {
        UUID tenantId = TenantContext.get();
        findPolicyOrThrow(policyNumber, tenantId);
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
        List<CoverageStatusView.ActiveCoverageView> coverages = coverageRepository.findByPolicyNumberAndActiveTrue(policyNumber).stream()
            .filter(c -> !"SURRENDER".equals(c.getBenefitType())) // openapi-policy.yaml's CoverageStatusView enum excludes SURRENDER
            .map(c -> new CoverageStatusView.ActiveCoverageView(BenefitType.valueOf(c.getBenefitType()), c.getSumAssuredAmount(), c.getSumAssuredCurrency()))
            .toList();
        return new CoverageStatusView(policyNumber, effectiveAsOf, coverages);
    }

    @Override
    public boolean isPolicyInForce(String policyNumber, LocalDate asOf) {
        // asOf is accepted (matches the OpenAPI query param and Po3's signature) but not
        // otherwise consulted -- this is a pure "is this policy currently ACTIVE-or-REINSTATED"
        // status read, not a date-bounded coverage-window computation (that's
        // getCoverageStatus's job, which separately filters `active` Coverage rows). Flagged.
        return findPolicyOrThrow(policyNumber, TenantContext.get()).isInForce();
    }

    @Override
    @Transactional
    public UUID reserveLoanValue(String policyNumber, BigDecimal amount, String currency, Duration ttl) {
        // M3 final review, I3. Task 7's Critical (a negative requestedAmount reaching
        // PolicyAccount.increaseEncumbrance, DECREASING loan_encumbrance_amount and thereby
        // RAISING the customer's own available loan value) was closed only at the HTTP
        // boundary, by policyloan.infrastructure.MoneyDto's @DecimalMin("0.01"). This guard
        // closes it at the module boundary instead. PolicyApi is a published @NamedInterface
        // that policyloan already calls and that M5's payment integration is documented to
        // call next; the check below at :~263 is an UPPER bound only
        // (amount.compareTo(available) > 0), which a negative amount passes trivially, so
        // without this line the next non-HTTP caller reintroduces the exploit with no
        // annotation anywhere to protect it. IllegalArgumentException maps to 400
        // VALIDATION_ERROR via GlobalExceptionHandler.handleValidation.
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Reservation amount must be a positive value, was: " + amount);
        }
        UUID tenantId = TenantContext.get();
        // Opportunistic TTL sweep (Module-Architecture-B1; Global Constraints -- a true cross-
        // tenant @Scheduled sweep is architecturally incompatible with this platform's fail-closed
        // RLS design, since a background thread with no TenantContext sees zero rows on every
        // RLS-protected table and there is no tenant-directory table to iterate). Runs inside the
        // CALLER's own TenantContext, so it is RLS-safe and expires only this tenant's stale
        // RESERVED rows for this policy -- self-healing the crash case (reserved, then crashed
        // before confirm/release) on the next real access instead of on a fixed wall-clock timer.
        loanValueReservationRepository.expireStaleReservations(policyNumber, tenantId, Instant.now());
        findPolicyOrThrow(policyNumber, tenantId);
        PolicyAccount account = policyAccountRepository.lockByPolicyNumber(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));

        // Uses the shared native aggregate query (added at the end of Task 1) rather than
        // pulling every RESERVED row into memory and reducing client-side -- same result,
        // one fewer place computing "sum of currently-RESERVED amounts" for this policy.
        BigDecimal currentlyReserved = loanValueReservationRepository.sumReservedAmountForPolicy(policyNumber, tenantId);
        BigDecimal available = account.availableLoanValue(currentlyReserved);
        if (amount.compareTo(available) > 0) {
            throw new InsufficientLoanValueException(
                "Requested " + amount + " " + currency + " exceeds available loan value " + available + " for policy " + policyNumber);
        }

        LoanValueReservation reservation = new LoanValueReservation(tenantId, policyNumber, amount, currency, Instant.now().plus(ttl));
        loanValueReservationRepository.save(reservation);
        return reservation.getReservationId();
    }

    @Override
    @Transactional
    public void confirmReservation(UUID reservationId) {
        UUID tenantId = TenantContext.get();
        // Task 3 review fix (Critical finding #1): PESSIMISTIC_WRITE on the reservation row
        // itself, taken BEFORE the status check and BEFORE the policy_account lock below --
        // closes the race where a concurrent opportunistic TTL sweep (reserveLoanValue)
        // flips this same row RESERVED -> EXPIRED between an unlocked read and this method's
        // write. Whichever transaction (this one, or the sweep's status-conditioned UPDATE)
        // gets here first wins and commits; the loser re-evaluates against the now-committed
        // state (the sweep's WHERE status='RESERVED' no longer matches a row this method just
        // confirmed; this method's status check below sees EXPIRED if the sweep won) instead of
        // blindly overwriting it. Lock ordering (reservation row, then policy_account) is
        // unchanged from before this fix and matches reserveLoanValue's sweep-then-account-lock
        // order, so this does not introduce a new deadlock class.
        LoanValueReservation reservation = loanValueReservationRepository.lockByReservationIdAndTenantId(reservationId, tenantId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!"RESERVED".equals(reservation.getStatus())) {
            // Explicitly rejected, not silently resurrected or made idempotent: a reservation
            // already CONFIRMED, RELEASED, or EXPIRED is a terminal state (see LoanValueReservation's
            // class Javadoc) and a second confirm attempt (e.g. a duplicate policyloan callback)
            // must surface as a clear domain error rather than double-applying the encumbrance
            // increase below.
            throw new InvalidPolicyStateException("Reservation " + reservationId + " is " + reservation.getStatus() + ", not RESERVED -- cannot confirm");
        }
        // Encumbrance updated synchronously here, not via async LoanOriginated consumption --
        // see Global Constraints.
        PolicyAccount account = policyAccountRepository.lockByPolicyNumber(reservation.getPolicyNumber())
            .orElseThrow(() -> new PolicyNotFoundException(reservation.getPolicyNumber()));
        account.increaseEncumbrance(reservation.getAmount());
        policyAccountRepository.save(account);

        reservation.confirm();
        loanValueReservationRepository.save(reservation);
    }

    @Override
    @Transactional
    public void releaseEncumbrance(String policyNumber, BigDecimal amount, String currency) {
        // M4's ledger note ("loan_encumbrance_amount only ever increases in M3 -- no
        // repayment-side consumption path") is now partially closed here, for the FAILURE
        // direction only: a disbursement that never actually paid out must not permanently
        // consume the policyholder's loan value. Full repayment-side consumption (decrementing
        // encumbrance as the loan is repaid) remains out of scope for M5.
        UUID tenantId = TenantContext.get();
        // Same lockByPolicyNumber PESSIMISTIC_WRITE discipline confirmReservation uses -- the
        // caller (policyloan.PolicyLoanApiImpl.markDisbursementFailed) already announces its own
        // state change, so nothing is published from here.
        PolicyAccount account = policyAccountRepository.lockByPolicyNumber(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        account.decreaseEncumbrance(amount);
        policyAccountRepository.save(account);
    }

    @Override
    @Transactional
    public void releaseReservation(UUID reservationId) {
        UUID tenantId = TenantContext.get();
        // Same PESSIMISTIC_WRITE fix as confirmReservation above, for the identical race against
        // the TTL sweep.
        LoanValueReservation reservation = loanValueReservationRepository.lockByReservationIdAndTenantId(reservationId, tenantId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if ("CONFIRMED".equals(reservation.getStatus())) {
            throw new InvalidPolicyStateException("Reservation " + reservationId + " is already CONFIRMED -- cannot release a confirmed reservation");
        }
        if ("RESERVED".equals(reservation.getStatus())) {
            reservation.release();
            loanValueReservationRepository.save(reservation);
        }
        // Already RELEASED or EXPIRED -- idempotent no-op (unlike confirmReservation's explicit
        // rejection above), so a caller retrying after a network timeout on a first,
        // actually-successful release -- or racing the TTL sweep to the same terminal outcome --
        // doesn't get a spurious error.
    }

    @Override
    @Transactional
    public void suspendPolicy(String policyNumber, String reason, String suspendedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Deliverable 3 Rev 2 §3's flagged, unresolved "which product categories support
        // SUSPENDED" item, resolved here as a configurable refdata code set (Task 6's
        // db-migrations/refdata/V2) rather than a hardcoded category list.
        List<ReferenceCodeView> eligibleCategories = referenceDataApi.getCodes("POLICY_SUSPENSION_ELIGIBLE_CATEGORIES");
        boolean eligible = eligibleCategories.stream().anyMatch(c -> c.code().equals(policy.getProductCategory()));
        if (!eligible) {
            throw new InvalidPolicyStateException("Product category " + policy.getProductCategory() + " is not eligible for SUSPENDED status");
        }
        policy.suspend(reason);
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicySuspended", tenantId,
            Map.of("policyNumber", policyNumber, "suspendedAt", policy.getSuspendedAt().toString(), "reason", reason)));
    }

    @Override
    @Transactional
    public void resumeSuspendedPolicy(String policyNumber, String resumedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        policy.resume();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyResumed", tenantId,
            Map.of("policyNumber", policyNumber, "resumedAt", Instant.now().toString())));
    }

    @Override
    @Transactional
    public void lapsePolicy(String policyNumber, String lapsedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        policy.lapse();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyLapsed", tenantId,
            Map.of("policyNumber", policyNumber, "lapsedAt", policy.getLapsedAt().toString())));
    }

    @Override
    public boolean isLapsable(String policyNumber) {
        return findPolicyOrThrow(policyNumber, TenantContext.get()).canLapse();
    }

    @Override
    @Transactional
    public void reinstatePolicy(String policyNumber, String reinstatedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Real bug, found by PolicyContractTest's own negative control: getLapsedAt() is null on
        // any non-LAPSED policy, so the window check below must never run before this guard --
        // it used to, and reinstating a never-lapsed (e.g. ACTIVE) policy NPE'd into a bare 500
        // instead of the 409 INVALID_POLICY_STATE Policy.reinstate()'s own guard would have given.
        if (!"LAPSED".equals(policy.getStatus())) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be LAPSED to be REINSTATED (current: " + policy.getStatus() + ")");
        }
        int windowMonths = Integer.parseInt(referenceDataApi.getValue("TZ_REINSTATEMENT_WINDOW_MONTHS", "TZ"));
        long monthsSinceLapse = Period.between(policy.getLapsedAt().atZone(ZoneOffset.UTC).toLocalDate(), LocalDate.now()).toTotalMonths();
        if (monthsSinceLapse > windowMonths) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " lapsed " + monthsSinceLapse
                + " months ago, exceeding the " + windowMonths + "-month reinstatement window (TZ_REINSTATEMENT_WINDOW_MONTHS, a PLACEHOLDER pending B1 sign-off)");
        }
        policy.reinstate();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyReinstated", tenantId,
            Map.of("policyNumber", policyNumber, "reinstatedAt", Instant.now().toString())));
    }

    @Override
    @Transactional
    public void markMatured(String policyNumber, String maturedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Either terminal status, not just MATURED (M6 final-review C1, part 2): Policy.mature()
        // now treats an already-SURRENDERED policy as a satisfied post-condition and leaves the
        // status alone, so publishing PolicyMatured for it would announce a transition that did not
        // happen. "Already closed" is the condition that suppresses the event, exactly as "already
        // in MY target status" did before the guards were widened.
        boolean alreadyClosed = "MATURED".equals(policy.getStatus()) || "SURRENDERED".equals(policy.getStatus());
        policy.mature();
        policyRepository.save(policy);
        if (alreadyClosed) {
            return; // idempotent on repeat -- no second event
        }
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyMatured", tenantId,
            Map.of("policyNumber", policyNumber,
                   "maturedAt", Instant.now().toString())));
    }

    @Override
    @Transactional(readOnly = true)
    public ClaimableCoverView claimableCover(String policyNumber, UUID policyMemberId, LocalDate asOf,
                                              String benefitType) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);

        // Keyed on the scheme ROW, for the same reason terminateForSettledClaim is: a GROUP_LIFE
        // policy issued through the ordinary path has no schedule under it, and a contract with
        // no members is valued from its own sum assured like any other.
        Optional<GroupScheme> scheme = "GROUP_LIFE".equals(policy.getProductCategory())
            ? groupSchemeRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            : Optional.empty();

        if (scheme.isEmpty()) {
            if (policyMemberId != null) {
                throw new InvalidPolicyStateException("Policy " + policyNumber
                    + " is not a group scheme, so a claim on it cannot name a member");
            }
            // The coverage for THIS benefit, not the policy's single sum assured. Returning the
            // latter is how a critical-illness claim came to be valued at the full death benefit.
            return coverageRepository.findByPolicyNumberAndActiveTrue(policyNumber).stream()
                .filter(c -> c.getBenefitType().equals(benefitType))
                .findFirst()
                .map(c -> new ClaimableCoverView(c.getSumAssuredAmount(), c.getSumAssuredCurrency(), null))
                .orElseThrow(() -> new InvalidPolicyStateException("Policy " + policyNumber
                    + " has no active " + benefitType + " cover to claim against. The product this"
                    + " policy was issued on does not cover it, so there is no amount to pay --"
                    + " paying one anyway is how a product pays for cover it never priced."));
        }
        // The scheme branch below values from the MEMBER SCHEDULE, not from coverage rows, so a
        // non-DEATH claim on a scheme is valued at the member's cover. That is a known limitation
        // carried from before this change rather than a considered design -- group products on
        // this platform author only DEATH.
        if (policyMemberId == null) {
            throw new InvalidPolicyStateException("Scheme " + policyNumber
                + " insures many lives, so a claim on it names a member");
        }

        PolicyMember member = policyMemberRepository
            .findByPolicyMemberIdAndTenantId(policyMemberId, tenantId)
            .filter(m -> m.getPolicyNumber().equals(policyNumber))
            .orElseThrow(() -> new InvalidPolicyStateException("Member " + policyMemberId
                + " is not a member of scheme " + policyNumber));

        // Covered ON THE DATE OF EVENT, not covered today. An exited member is a legitimate
        // claimant for an event that happened while they were still on the schedule -- which is
        // exactly why V9 keeps exited rows rather than deleting them.
        if (asOf.isBefore(member.getJoinedOn())
                || (member.getLeftOn() != null && asOf.isAfter(member.getLeftOn()))) {
            throw new InvalidPolicyStateException("Member " + policyMemberId
                + " was not covered on " + asOf + " (covered from " + member.getJoinedOn()
                + (member.getLeftOn() != null ? " to " + member.getLeftOn() : "") + ")");
        }

        // covered_amount, NOT benefit_amount, and NOT re-capped at the free cover limit. The
        // stored covered amount already IS the capped figure where the limit bit --
        // EVIDENCE_REQUIRED and DECLINED both sit at the limit, WITHIN_FCL and ACCEPTED sit at
        // the full benefit. Applying the limit again here would halve an excess an underwriter
        // had granted.
        //
        // findInForce is (memberId, tenantId, asOf, Pageable), newest-effective first; the
        // Pageable is how it takes only the row in force. This is its first caller: the bulk
        // sibling findInForceForMembers backs the member list, while findInForce itself was
        // written for exactly this and has never been invoked.
        BigDecimal covered = policyMemberBenefitRepository
            .findInForce(policyMemberId, tenantId, asOf, PageRequest.of(0, 1))
            .stream().findFirst()
            .map(PolicyMemberBenefit::getCoveredAmount)
            .orElseThrow(() -> new InvalidPolicyStateException("Member " + policyMemberId
                + " has no benefit in force on " + asOf));

        return new ClaimableCoverView(covered, scheme.get().getCurrency(), policyMemberId);
    }

    @Override
    @Transactional
    public void dischargeForSettledClaim(String policyNumber, UUID policyMemberId, LocalDate dateOfEvent,
                                          UUID claimId, String dischargedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // A SCHEME IS NOT DISCHARGED BY ONE MEMBER'S DEATH.
        //
        // Closing a policy here is right because a settled claim has discharged its coverage,
        // and billing must then stop invoicing a contract that no longer covers anybody. Both
        // halves are true of individual life and false of a group scheme: the claim discharged
        // ONE member, the other lives are alive and insured, and the employer still owes premium
        // for them -- so billing continuing is the CORRECT outcome here, not the problem this
        // closure was written to prevent.
        //
        // Without the branch below, one member's settled death claim set the master policy to
        // SURRENDERED and uninsured the entire workforce. Silently, from an AFTER_COMMIT
        // listener, with the claim money already paid. No test covered a claim on a scheme,
        // which is how it survived.
        //
        // BOTH CONDITIONS, and each is here for its own reason.
        //
        // The SCHEME ROW is the real test, and an existing case proves category alone is wrong:
        // terminateForSettledClaimClosesASuspendedPolicy issues a GROUP_LIFE policy through the
        // ordinary issuePolicy path -- GROUP_LIFE being the only category refdata/V2 seeds into
        // POLICY_SUSPENSION_ELIGIBLE_CATEGORIES, so it is that test's only route to a genuinely
        // SUSPENDED policy. It has no group_scheme row and no members. A contract with no member
        // schedule has no member to discharge instead, so it closes like any other.
        //
        // The CATEGORY is the cheap pre-filter, and it is load-bearing rather than an
        // optimisation: it keeps an individual policy from touching policy.group_scheme at all.
        // Almost every policy on the platform is individual, and several test classes that settle
        // claims never apply policy/V9 -- for them the table does not exist, and an unconditional
        // query throws inside an AFTER_COMMIT listener whose only response is to raise
        // POLICY_CLOSURE_FAILED and leave a settled claim's policy open. Found exactly that way.
        Optional<GroupScheme> scheme = "GROUP_LIFE".equals(policy.getProductCategory())
            ? groupSchemeRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            : Optional.empty();
        if (scheme.isPresent()) {
            dischargeMember(policy, scheme.get(), policyMemberId, dateOfEvent, claimId, tenantId);
            return;
        }
        closeAsSurrendered(policy, claimId, tenantId);
    }

    /**
     * No insured life remains: the contract is discharged and billing must stop invoicing it.
     *
     * <p>Reached from both branches, because it is the same fact either way — an individual
     * policy whose one life died, or a scheme whose LAST life did. Note the difference from the
     * bug this whole change exists to fix: that closed a scheme when ONE OF MANY members died.
     * Closing when the last one does is the mirror of it, not a repeat.
     */
    private void closeAsSurrendered(Policy policy, UUID claimId, UUID tenantId) {
        // Either terminal status, not just SURRENDERED -- same M6 final-review C1 part 2 reasoning
        // as markMatured above (a policy already MATURED stays MATURED, so no PolicySurrendered).
        boolean alreadyClosed = "SURRENDERED".equals(policy.getStatus()) || "MATURED".equals(policy.getStatus());
        policy.terminateForSettledClaim();
        policyRepository.save(policy);
        if (alreadyClosed) {
            return; // idempotent on repeat -- no second event
        }
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicySurrendered", tenantId,
            Map.of("policyNumber", policy.getPolicyNumber(),
                   "claimId", claimId,
                   "surrenderedAt", Instant.now().toString())));
    }

    /**
     * The group half of {@link #dischargeForSettledClaim}: one life leaves, the contract stays.
     *
     * <p>Dated to the EVENT, not to the payment run. A death in March settled in September means
     * the member stopped being covered in March, and {@code totalCovered} counts only ACTIVE
     * members — so dating the exit to September would have left them in the scheme's sum assured
     * for six months they were not alive.
     */
    private void dischargeMember(Policy policy, GroupScheme scheme, UUID policyMemberId,
                                  LocalDate dateOfEvent, UUID claimId, UUID tenantId) {
        if (policyMemberId == null) {
            // Cannot happen through the claims path -- registration refuses a memberless claim on
            // a scheme -- but this is a published API and a silent no-op would leave a paid claim
            // with nobody discharged and no trace of why.
            throw new InvalidPolicyStateException("Scheme " + policy.getPolicyNumber()
                + " insures many lives, so discharging a settled claim on it names a member");
        }
        PolicyMember member = policyMemberRepository
            .findByPolicyMemberIdAndTenantId(policyMemberId, tenantId)
            .filter(m -> m.getPolicyNumber().equals(policy.getPolicyNumber()))
            .orElseThrow(() -> new InvalidPolicyStateException("Member " + policyMemberId
                + " is not a member of scheme " + policy.getPolicyNumber()));

        if (MemberStatus.EXITED.name().equals(member.getStatus())) {
            return; // idempotent on redelivery, mirroring the alreadyClosed flag on the other branch
        }

        member.exit(dateOfEvent);
        policyMemberRepository.save(member);
        // Flush before restating so the total sees the exit. Both inside this transaction: a
        // scheme must never be readable with the member gone and the total still counting them.
        policyMemberBenefitRepository.flush();

        // WAS THAT THE LAST LIFE? If so the scheme covers nobody, and restating its total is not
        // merely wrong but impossible: totalCovered sums only ACTIVE members and returns NULL
        // with none, which restateSumAssured refuses ("an empty scheme is a scheme to close, not
        // one to carry at nil").
        //
        // So it closes, exactly the way an individual policy does when its one life dies --
        // SURRENDERED, and billing stops. That is the same fact stated about a different
        // contract shape, and it is the MIRROR of the bug this method exists to fix rather than
        // a return to it: that one closed a scheme when ONE OF MANY died.
        //
        // The stored sum assured deliberately keeps its last positive value rather than going to
        // zero -- policy_sum_assured_positive forbids zero, and a closed contract keeping the
        // figure it was last insured for is how every other closed policy on this platform reads.
        boolean noLivesRemain = policyMemberRepository.countByTenantIdAndPolicyNumberAndStatus(
            tenantId, policy.getPolicyNumber(), MemberStatus.ACTIVE.name()) == 0;
        BigDecimal total = noLivesRemain
            ? BigDecimal.ZERO
            : restateSchemeTotal(policy, tenantId, LocalDate.now());

        // No consumer yet, and that is a known gap rather than a new one: regreporting does not
        // listen to policy.GroupMemberAdded either, so policy_dimension's sum assured already
        // goes stale on a joiner. This adds a second route to the same staleness.
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.GroupMemberExited", tenantId,
            Map.of("policyNumber", policy.getPolicyNumber(),
                   "policyMemberId", policyMemberId,
                   "leftOn", dateOfEvent.toString(),
                   "reason", "CLAIM_SETTLED",
                   "schemeTotalCovered", Map.of("amount", total.toPlainString(),
                       "currencyCode", scheme.getCurrency()))));

        // Published AFTER the exit, and in addition to it, because two things happened: this
        // member left, and the contract then had nobody left to insure. A consumer tracking
        // membership needs the first; billing needs the second.
        if (noLivesRemain) {
            log.info("Scheme {} has no covered lives left after member {} was discharged -- closing it",
                policy.getPolicyNumber(), policyMemberId);
            closeAsSurrendered(policy, claimId, tenantId);
        }
    }

    private List<Beneficiary> validateAndBuildBeneficiaries(UUID tenantId, String policyNumber, List<BeneficiaryInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        BigDecimal totalShare = BigDecimal.ZERO;
        List<Beneficiary> built = new ArrayList<>();
        for (BeneficiaryInput input : inputs) {
            boolean hasParty = input.partyId() != null;
            boolean hasFreeform = input.freeformDesignee() != null && !input.freeformDesignee().isBlank();
            if (hasParty == hasFreeform) { // both true or both false -- neither is valid
                throw new BeneficiaryValidationException("Each beneficiary must have exactly one of partyId or freeformDesignee, not both or neither");
            }
            if (input.type() == BeneficiaryType.PARTY && !hasParty) {
                throw new BeneficiaryValidationException("Beneficiary type PARTY requires partyId");
            }
            if (input.type() == BeneficiaryType.FREEFORM && !hasFreeform) {
                throw new BeneficiaryValidationException("Beneficiary type FREEFORM requires freeformDesignee");
            }
            totalShare = totalShare.add(input.sharePercent());
            built.add(new Beneficiary(tenantId, policyNumber, input.type().name(), input.partyId(), input.freeformDesignee(),
                input.sharePercent(), input.revocable()));
        }
        if (totalShare.compareTo(new BigDecimal("100")) != 0) {
            throw new BeneficiaryValidationException("Beneficiary shares must sum to 100, got " + totalShare);
        }
        return built;
    }

    // =================================================================================
    // Group business
    // =================================================================================

    @Override
    @Transactional
    public GroupSchemeView issueGroupScheme(IssueGroupSchemeRequest request, String issuedBy) {
        UUID tenantId = TenantContext.get();
        partyApi.getParty(request.policyholderPartyId()); // the employer must exist
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(request.productId(), LocalDate.now());
        if (snapshot.category() != ProductCategory.GROUP_LIFE) {
            // Without this a scheme could be hung off a term-life product, and every
            // reader downstream that branches on category -- reserving, reporting,
            // commission -- would treat 500 lives as one.
            throw new InvalidPolicyStateException(
                "A group scheme needs a GROUP_LIFE product; this one is " + snapshot.category());
        }

        LocalDate today = LocalDate.now();
        LocalDate commencement = request.commencementDate() != null ? request.commencementDate() : today;
        if (commencement.isAfter(today)) {
            throw new InvalidPolicyStateException(
                "A scheme cannot commence in the future yet: its sum assured is the total of its members' "
                    + "cover, and until commencement that total would be nil while the contract said otherwise");
        }

        List<MemberInput> schedule = request.openingSchedule() != null ? request.openingSchedule() : List.of();
        if (schedule.isEmpty()) {
            throw new InvalidPolicyStateException(
                "A scheme must be issued with at least one member: its sum assured is the total of its "
                    + "members' cover, and a scheme insuring nobody has none");
        }

        Map<String, BigDecimal> gradeTable = gradeTableFor(request.benefitBasis(), request.grades());

        // Value EVERYBODY before writing anything. A 400-row schedule with one bad row
        // must not leave 399 members and a scheme priced for them behind -- and the
        // valuation is where a bad row shows up, since it is the only step that reads
        // each member's own numbers.
        List<ValuedMember> valued = new ArrayList<>(schedule.size());
        Set<UUID> seen = new HashSet<>();
        for (MemberInput input : schedule) {
            if (input.memberPartyId() == null) {
                throw new InvalidPolicyStateException("Every row of the opening schedule must name a person");
            }
            if (!seen.add(input.memberPartyId())) {
                // ux_policy_member_active would catch this as a constraint violation. Here
                // it arrives as a sentence naming the duplicate, which is what somebody
                // fixing a spreadsheet needs.
                throw new InvalidPolicyStateException(
                    "Party " + input.memberPartyId() + " appears twice on the opening schedule");
            }
            valued.add(new ValuedMember(input,
                valueMember(request.benefitBasis(), request.flatBenefitAmount(), request.salaryMultiple(),
                    request.fclAmount(), gradeTable, input)));
        }

        BigDecimal total = valued.stream()
            .map(v -> v.valuation().coveredAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // GRP- rather than POL-: a scheme is visibly a scheme to whoever reads the number
        // off a call. Same placeholder generation strategy as issuePolicy, and the same
        // flag applies -- no sequence generator is wired here yet.
        String policyNumber = "GRP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Policy policy = new Policy(policyNumber, tenantId, request.policyholderPartyId(), request.productId(),
            request.productVersionId(), snapshot.category().name(),
            agentOfRecordFor(request.policyholderPartyId(), request.agentOfRecordId()),
            total, request.currency(), request.premiumAmount(), request.premiumCurrency(),
            request.premiumFrequency(), null, issuedBy);
        policy.applyTerm(commencement, request.policyTermMonths(), null);
        // Deliberately no recordLifeAssured: migration V8. An employer is not a life
        // assured, and the lives are the schedule below.
        policy.recordIssuedOn(today);
        // AN OFFER, not cover, unless the basis already carries cover. Identical to
        // issuePolicy: a scheme is a contract an employer accepts by paying for it, and the
        // first cleared premium is that acceptance.
        //
        // This REVERSES build5 §2.6, which said "group schemes stay outside
        // offer-and-acceptance ... so it goes on risk at issuance as it always has". That was
        // written when POST /group-schemes was the only way a scheme could exist: with no
        // underwriting pipeline behind it, waiting for a premium would have meant nobody was
        // ever covered. Group business is proposed, assessed and decided now, so the last step
        // is the same one individual business takes.
        //
        // activateOnFirstPremium needs NO change: it activates any PROPOSED policy, and
        // billing still raises the employer's schedule off PolicyIssued exactly as before.
        boolean startsCoverNow = request.issuanceBasis() != null
            && request.issuanceBasis().startsCoverImmediately();
        if (startsCoverNow) {
            policy.activate();
        }
        policyRepository.save(policy);

        policyAccountRepository.save(new PolicyAccount(policyNumber, tenantId, BigDecimal.ZERO, request.currency()));
        // Deliberately NOT per-benefit. A scheme's claimable cover derives from its member
        // schedule -- claimableCover branches on the scheme before it looks at coverage at all --
        // so per-benefit rows here would be decorative.
        coverageRepository.save(new Coverage(tenantId, policyNumber, BenefitType.DEATH.name(), total, request.currency()));

        groupSchemeRepository.save(new GroupScheme(policyNumber, tenantId, request.benefitBasis(),
            request.flatBenefitAmount(), request.salaryMultiple(), request.fclAmount(),
            request.currency(), issuedBy));
        if (request.benefitBasis() == BenefitBasis.GRADED) {
            request.grades().forEach(g -> groupSchemeGradeRepository.save(
                new GroupSchemeGrade(tenantId, policyNumber, g.gradeCode(), g.benefitAmount())));
        }

        for (ValuedMember v : valued) {
            LocalDate joinedOn = v.input().joinedOn() != null ? v.input().joinedOn() : commencement;
            if (joinedOn.isAfter(today)) {
                throw new InvalidPolicyStateException(
                    "Member " + v.input().memberPartyId() + " cannot join in the future");
            }
            persistMember(tenantId, policyNumber, v.input(), v.valuation(), joinedOn, issuedBy);
        }

        // The same payload issuePolicy emits, because a scheme needs everything an
        // individual policy needs downstream: billing raises the employer's invoice
        // schedule from it, distribution accrues the broker's commission, and
        // regreporting writes the policy_dimension row the regulator's return reads. A
        // group policy that skipped this would be invisible to all three.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", request.policyholderPartyId());
        payload.put("productId", request.productId());
        payload.put("productVersionId", request.productVersionId());
        payload.put("sumAssured", Map.of("amount", total.toPlainString(), "currencyCode", request.currency()));
        payload.put("issueDate", policy.getIssueDate().toString());
        payload.put("premium", Map.of("amount", request.premiumAmount().toPlainString(), "currencyCode", request.premiumCurrency()));
        payload.put("premiumFrequency", request.premiumFrequency());
        // The RESOLVED agent, off the policy, not the raw request value -- see issuePolicy's
        // own note on why the two must not disagree.
        payload.put("agentOfRecordId", policy.getAgentOfRecordId());
        // PROPOSED for an ordinary scheme, ACTIVE for a basis that already carries cover --
        // no longer always the same value, which is why it was already reading the policy's
        // own status rather than the literal the comment here used to claim.
        //
        // Load-bearing downstream: communication's offerMade branches on exactly this key, so
        // an employer now receives the offer message and its deadline. That is the point.
        payload.put("status", policy.getStatus());
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyIssued", tenantId, payload));

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.GroupSchemeIssued", tenantId, Map.of(
            "policyNumber", policyNumber,
            "benefitBasis", request.benefitBasis().name(),
            "memberCount", valued.size(),
            "totalCovered", Map.of("amount", total.toPlainString(), "currencyCode", request.currency()))));

        // Read back through the derived path rather than returning the figure just
        // computed, so what issuance answers with is exactly what the scheme page will
        // show. If the two ever disagree, the caller sees it immediately instead of a
        // week later on a reconciliation.
        policyMemberBenefitRepository.flush();
        return getGroupScheme(policyNumber);
    }

    @Override
    public GroupSchemeView getGroupScheme(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        GroupScheme scheme = findSchemeOrThrow(policyNumber, tenantId);

        long activeMembers = policyMemberRepository
            .countByTenantIdAndPolicyNumberAndStatus(tenantId, policyNumber, MemberStatus.ACTIVE.name());
        long awaitingEvidence = policyMemberRepository
            .countByTenantIdAndPolicyNumberAndStatusAndUnderwritingStatus(tenantId, policyNumber,
                MemberStatus.ACTIVE.name(), MemberUnderwritingStatus.EVIDENCE_REQUIRED);
        BigDecimal totalCovered = policyMemberBenefitRepository
            .totalCovered(tenantId, policyNumber, LocalDate.now());

        List<GroupSchemeGradeView> grades = groupSchemeGradeRepository
            .findByTenantIdAndPolicyNumberOrderByGradeCode(tenantId, policyNumber).stream()
            .map(g -> new GroupSchemeGradeView(g.getGradeCode(), g.getBenefitAmount()))
            .toList();

        return new GroupSchemeView(policyNumber, policy.getPolicyholderPartyId(),
            PolicyStatus.valueOf(policy.getStatus()), policy.getCommencementDate(),
            policy.getPolicyTermMonths(), scheme.getBenefitBasis(), scheme.getFlatBenefitAmount(),
            scheme.getSalaryMultiple(), scheme.getFclAmount(), scheme.getCurrency(),
            activeMembers, totalCovered, awaitingEvidence, grades);
    }

    @Override
    public Page<PolicyMemberView> listMembers(String policyNumber, MemberStatus status, String q,
                                               Pageable pageable) {
        UUID tenantId = TenantContext.get();
        findPolicyOrThrow(policyNumber, tenantId);
        findSchemeOrThrow(policyNumber, tenantId);

        String currency = findSchemeOrThrow(policyNumber, tenantId).getCurrency();

        /*
         * The name search, resolved across the module boundary: a member row carries a
         * party id and no name, so the party module answers "which parties are called
         * something like this" and the roll is filtered on the ids it returns.
         *
         * The empty result is handled HERE rather than in the query. An empty set means
         * "no party has that name", which for this roll means no member matches -- but an
         * empty collection in a SQL `IN` is a syntax error, and a null one would mean the
         * opposite ("no name filter") and return the entire schedule for a search that
         * matched nobody. Same hazard the benefit lookup below already guards.
         */
        Set<UUID> nameMatches = null;
        if (q != null && !q.isBlank()) {
            nameMatches = partyApi.partyIdsMatchingName(q);
            if (nameMatches.isEmpty()) {
                return Page.empty(pageable);
            }
        }

        Page<PolicyMember> members = policyMemberRepository.findMembers(
            tenantId, policyNumber, status != null ? status.name() : null, nameMatches, pageable);
        if (members.isEmpty()) {
            // Short-circuit rather than pass an empty list to an IN clause, which is a
            // Postgres syntax error rather than an empty result.
            return members.map(m -> toMemberView(m, null, currency));
        }

        // One query for the whole page. Resolving each member's benefit individually
        // would be 25 round trips to draw a page and 500 to draw the schedule.
        Map<UUID, PolicyMemberBenefitRepository.InForceBenefitRow> benefits =
            policyMemberBenefitRepository.findInForceForMembers(tenantId,
                    members.getContent().stream().map(PolicyMember::getPolicyMemberId).toList(),
                    LocalDate.now())
                .stream()
                .collect(Collectors.toMap(
                    PolicyMemberBenefitRepository.InForceBenefitRow::getPolicyMemberId, r -> r));

        return members.map(m -> toMemberView(m, benefits.get(m.getPolicyMemberId()), currency));
    }

    @Override
    @Transactional
    public PolicyMemberView addMember(String policyNumber, MemberInput member, String addedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        GroupScheme scheme = findSchemeOrThrow(policyNumber, tenantId);
        if (!policy.isInForce()) {
            throw new InvalidPolicyStateException("Scheme " + policyNumber
                + " must be in force to add a member (current: " + policy.getStatus() + ")");
        }
        if (member.memberPartyId() == null) {
            throw new InvalidPolicyStateException("A member must name a person");
        }
        partyApi.getParty(member.memberPartyId());
        if (policyMemberRepository.existsByTenantIdAndPolicyNumberAndMemberPartyIdAndStatus(
                tenantId, policyNumber, member.memberPartyId(), MemberStatus.ACTIVE.name())) {
            throw new InvalidPolicyStateException("That person is already an active member of scheme " + policyNumber);
        }

        LocalDate today = LocalDate.now();
        LocalDate joinedOn = member.joinedOn() != null ? member.joinedOn() : today;
        if (joinedOn.isAfter(today)) {
            // Backdating is normal -- a schedule reaches the insurer weeks after somebody
            // started. Forward-dating is not supported until the scheme total is date-aware.
            throw new InvalidPolicyStateException(
                "A member cannot be added with a future join date; record them on the day cover starts");
        }
        if (policy.getCommencementDate() != null && joinedOn.isBefore(policy.getCommencementDate())) {
            throw new InvalidPolicyStateException("A member cannot join before the scheme commenced on "
                + policy.getCommencementDate());
        }

        GroupBenefitCalculator.Valuation valuation = valueMember(scheme.getBenefitBasis(),
            scheme.getFlatBenefitAmount(), scheme.getSalaryMultiple(), scheme.getFclAmount(),
            gradeTableFor(tenantId, policyNumber, scheme.getBenefitBasis()), member);

        PolicyMember saved = persistMember(tenantId, policyNumber, member, valuation, joinedOn, addedBy);

        // Flush so the derived total below sees the row just written, then restate the
        // contract total from it -- inside this transaction, so the master policy and its
        // member schedule cannot disagree even for an instant.
        policyMemberBenefitRepository.flush();
        BigDecimal total = restateSchemeTotal(policy, tenantId, today);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.GroupMemberAdded", tenantId, Map.of(
            "policyNumber", policyNumber,
            "memberPartyId", member.memberPartyId(),
            "joinedOn", joinedOn.toString(),
            "coveredAmount", Map.of("amount", valuation.coveredAmount().toPlainString(),
                "currencyCode", scheme.getCurrency()),
            "underwritingStatus", valuation.underwritingStatus().name(),
            "schemeTotalCovered", Map.of("amount", total.toPlainString(),
                "currencyCode", scheme.getCurrency()))));

        return toMemberView(saved, valuation, member.salaryAmount(), scheme.getCurrency(), joinedOn);
    }

    /** One opening-schedule row and what the scheme's basis makes of it. */
    private record ValuedMember(MemberInput input, GroupBenefitCalculator.Valuation valuation) {}

    /**
     * Check one member's inputs against the scheme's basis, then value them.
     *
     * <p>The rejections matter as much as the arithmetic: a grade code on a flat scheme,
     * or a salary on a graded one, is a caller who believes something about this contract
     * that is not true. Accepting and ignoring it would let a scheme administrator upload
     * a salaried schedule to a flat scheme and see plausible, wrong numbers.
     */
    private GroupBenefitCalculator.Valuation valueMember(BenefitBasis basis, BigDecimal flatBenefitAmount,
                                                          BigDecimal salaryMultiple, BigDecimal fclAmount,
                                                          Map<String, BigDecimal> gradeTable, MemberInput member) {
        BigDecimal gradeBenefit = null;
        switch (basis) {
            case FLAT -> {
                rejectPresent(member.gradeCode(), "This scheme pays a flat benefit, so a grade means nothing on it");
                rejectPresent(member.salaryAmount(), "This scheme pays a flat benefit, so a salary means nothing on it");
            }
            case SALARY_MULTIPLE -> {
                rejectPresent(member.gradeCode(), "This scheme values members by salary, so a grade means nothing on it");
                if (member.salaryAmount() == null) {
                    throw new InvalidPolicyStateException(
                        "This scheme values members at " + salaryMultiple + "x salary, so every member needs one");
                }
            }
            case GRADED -> {
                rejectPresent(member.salaryAmount(), "This scheme values members by grade, so a salary means nothing on it");
                if (member.gradeCode() == null) {
                    throw new InvalidPolicyStateException("This scheme values members by grade, so every member needs one");
                }
                gradeBenefit = gradeTable.get(member.gradeCode());
                if (gradeBenefit == null) {
                    throw new InvalidPolicyStateException("Grade " + member.gradeCode()
                        + " is not on this scheme's grade table (" + String.join(", ", gradeTable.keySet()) + ")");
                }
            }
        }
        try {
            BigDecimal benefit = GroupBenefitCalculator.benefitFor(
                basis, flatBenefitAmount, salaryMultiple, member.salaryAmount(), gradeBenefit);
            return GroupBenefitCalculator.evaluate(benefit, fclAmount);
        } catch (IllegalArgumentException e) {
            // The calculator speaks in domain terms already; re-wrapped so a bad request
            // reaches the caller as 409 rather than as a 500.
            throw new InvalidPolicyStateException(e.getMessage());
        }
    }

    private static void rejectPresent(Object value, String message) {
        if (value != null) throw new InvalidPolicyStateException(message);
    }

    /** The grade table as supplied on an issue request, validated. Empty unless GRADED. */
    private Map<String, BigDecimal> gradeTableFor(BenefitBasis basis, List<GradeInput> grades) {
        if (basis != BenefitBasis.GRADED) {
            if (grades != null && !grades.isEmpty()) {
                throw new InvalidPolicyStateException(
                    "Only a graded scheme has a grade table; this one is " + basis);
            }
            return Map.of();
        }
        if (grades == null || grades.isEmpty()) {
            throw new InvalidPolicyStateException("A graded scheme needs at least one grade to value anybody");
        }
        Map<String, BigDecimal> table = new LinkedHashMap<>();
        for (GradeInput g : grades) {
            if (g.gradeCode() == null || g.gradeCode().isBlank()) {
                throw new InvalidPolicyStateException("Every grade needs a code");
            }
            if (g.benefitAmount() == null || g.benefitAmount().signum() <= 0) {
                throw new InvalidPolicyStateException("Grade " + g.gradeCode() + " needs a positive benefit");
            }
            if (table.put(g.gradeCode(), g.benefitAmount()) != null) {
                throw new InvalidPolicyStateException("Grade " + g.gradeCode() + " is listed twice");
            }
        }
        return table;
    }

    /** The grade table as stored. Empty unless GRADED, where it is loaded from the scheme. */
    private Map<String, BigDecimal> gradeTableFor(UUID tenantId, String policyNumber, BenefitBasis basis) {
        if (basis != BenefitBasis.GRADED) return Map.of();
        Map<String, BigDecimal> table = new LinkedHashMap<>();
        groupSchemeGradeRepository.findByTenantIdAndPolicyNumberOrderByGradeCode(tenantId, policyNumber)
            .forEach(g -> table.put(g.getGradeCode(), g.getBenefitAmount()));
        return table;
    }

    private PolicyMember persistMember(UUID tenantId, String policyNumber, MemberInput input,
                                        GroupBenefitCalculator.Valuation valuation, LocalDate joinedOn,
                                        String createdBy) {
        PolicyMember member = policyMemberRepository.save(new PolicyMember(tenantId, policyNumber,
            input.memberPartyId(), input.gradeCode(), joinedOn, valuation.underwritingStatus(), createdBy));
        // The benefit is effective from the day cover starts for this member, not from
        // today: a schedule that arrives late still describes cover that began when the
        // person joined, and a claim in between is paid on this row.
        policyMemberBenefitRepository.save(new PolicyMemberBenefit(tenantId, member.getPolicyMemberId(),
            joinedOn, input.salaryAmount(), valuation.benefitAmount(), valuation.coveredAmount(), createdBy));
        return member;
    }

    /**
     * Restate the master policy's -- and its coverage row's -- sum assured from the member
     * schedule.
     *
     * <p>Both, because {@code getCoverageStatus} answers from the coverage row and the
     * policy list answers from the policy. Restating one and not the other would give the
     * platform two different answers to "how much is this scheme insured for" depending on
     * which screen you were standing in front of.
     */
    private BigDecimal restateSchemeTotal(Policy policy, UUID tenantId, LocalDate asOf) {
        BigDecimal total = policyMemberBenefitRepository.totalCovered(tenantId, policy.getPolicyNumber(), asOf);
        policy.restateSumAssured(total);
        coverageRepository.findByPolicyNumberAndActiveTrue(policy.getPolicyNumber()).stream()
            .filter(c -> BenefitType.DEATH.name().equals(c.getBenefitType()))
            .forEach(c -> c.restateSumAssured(total));
        return total;
    }

    private GroupScheme findSchemeOrThrow(String policyNumber, UUID tenantId) {
        return groupSchemeRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            // Distinct from not-found on purpose: "this is an individual policy" and
            // "there is no such policy" send whoever asked to different places.
            .orElseThrow(() -> new InvalidPolicyStateException(
                "Policy " + policyNumber + " is not a group scheme"));
    }

    private PolicyMemberView toMemberView(PolicyMember m, GroupBenefitCalculator.Valuation valuation,
                                           BigDecimal salaryAmount, String currency, LocalDate effectiveFrom) {
        return new PolicyMemberView(m.getPolicyMemberId(), m.getMemberPartyId(), m.getGradeCode(),
            m.getJoinedOn(), m.getLeftOn(), MemberStatus.valueOf(m.getStatus()),
            m.getUnderwritingStatus(), m.getUnderwritingCaseId(), salaryAmount,
            valuation.benefitAmount(), valuation.coveredAmount(), currency, effectiveFrom);
    }

    private PolicyMemberView toMemberView(PolicyMember m, PolicyMemberBenefitRepository.InForceBenefitRow benefit,
                                           String currency) {
        return new PolicyMemberView(m.getPolicyMemberId(), m.getMemberPartyId(), m.getGradeCode(),
            m.getJoinedOn(), m.getLeftOn(), MemberStatus.valueOf(m.getStatus()),
            m.getUnderwritingStatus(), m.getUnderwritingCaseId(),
            // Null across the money fields means a member whose cover has not started yet
            // as at today -- rendered as "not yet in force" rather than as a zero, which
            // would read as "insured for nothing".
            benefit != null ? benefit.getSalaryAmount() : null,
            benefit != null ? benefit.getBenefitAmount() : null,
            benefit != null ? benefit.getCoveredAmount() : null,
            currency,
            benefit != null ? benefit.getEffectiveFrom() : null);
    }

    private Policy findPolicyOrThrow(String policyNumber, UUID tenantId) {
        return policyRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
    }

    private PolicyView toView(Policy policy) {
        PolicyAccount account = policyAccountRepository.findById(policy.getPolicyNumber()).orElse(null);
        List<BeneficiaryView> beneficiaryViews = beneficiaryRepository.findByPolicyNumberAndActiveTrue(policy.getPolicyNumber()).stream()
            .map(b -> new BeneficiaryView(b.getBeneficiaryId(), BeneficiaryType.valueOf(b.getBeneficiaryType()), b.getPartyId(),
                b.getFreeformDesignee(), b.getSharePercent(), b.isRevocable()))
            .toList();
        return new PolicyView(policy.getPolicyNumber(), policy.getUnderwritingCaseId(), policy.getPolicyholderPartyId(), policy.getProductId(), policy.getProductVersionId(),
            policy.getAgentOfRecordId(), PolicyStatus.valueOf(policy.getStatus()), policy.getIssueDate(),
            policy.getSumAssuredAmount(), policy.getSumAssuredCurrency(),
            account != null ? account.getCashValueAmount() : BigDecimal.ZERO,
            account != null ? account.getCashValueCurrency() : policy.getSumAssuredCurrency(),
            policy.getPremiumAmount(), policy.getPremiumCurrency(), policy.getPremiumFrequency(),
            beneficiaryViews,
            policy.getCommencementDate(), policy.getPolicyTermMonths(),
            policy.getPremiumPayingTermMonths(), policy.getMaturityDate(),
            policy.getLifeAssuredPartyId(), policy.getProductCategory());
    }
}
