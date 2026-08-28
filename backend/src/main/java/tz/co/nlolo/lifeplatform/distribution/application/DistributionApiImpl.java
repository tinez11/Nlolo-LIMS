package tz.co.nlolo.lifeplatform.distribution.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.AgentNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionAccrualView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionRuleView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionValidationException;
import tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException;
import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.api.PlanStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionCalculator;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionPlan;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionRule;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionStatement;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.AgentProfileRepository;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionAccrualRepository;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionPlanRepository;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionRuleRepository;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionStatementRepository;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 5: {@link DistributionApi}'s full published surface -- agent onboarding, commission-plan
 * authoring, and the reads Task 9's REST layer exposes.
 *
 * <p>{@link CommissionStatementNotFoundException} (review fix) is the third named not-found type
 * alongside {@link AgentNotFoundException} and {@link CommissionPlanNotFoundException} -- every
 * lookup-by-id in this class that can miss has its own dedicated, HTTP-mappable exception, since
 * this module's whole purpose is to be Task 9's REST surface: a bare JDK exception here would
 * silently 500 instead of 404 unless that task happened to remember to add a handler for it.
 *
 * <p><b>Task 6 addendum.</b> {@link #resolveAgentWithPlan} and {@link #persistAccrual} are
 * package-private accrual plumbing that {@code PolicyEventListener} calls directly (injecting
 * this concrete class, not just {@link DistributionApi} -- the same shape
 * {@code billing.application.PolicyEventListener} already uses against {@code BillingApiImpl}):
 * they are not part of the published surface, only a way to reuse this class's already-wired
 * repositories instead of duplicating plan-resolution and statement bookkeeping in the listener.
 * {@link #resolveApplicablePlan} is the plan-precedence rule shared between the two -- the same
 * precedence {@link #getApplicablePlan} has always used, extracted rather than duplicated a
 * second time.
 */
@Service
public class DistributionApiImpl implements DistributionApi {

    /** Mirrors {@code agent_profile.license_number VARCHAR(50)} (distribution/V1) and
     * openapi-distribution.yaml's declared maxLength. Checked in Java so an over-long value is a
     * clear 422 rather than an integrity violation the layer below has to guess at. */
    private static final int MAX_LICENSE_NUMBER_LENGTH = 50;

    /** True only when the violation really is {@code ux_agent_license}. Postgres names the index
     * in its error, and Spring keeps the driver exception as the cause chain, so matching on the
     * index name is both specific and stable -- unlike catching the exception type alone, which
     * says nothing about WHICH constraint failed. */
    private static boolean mentionsLicenseIndex(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("ux_agent_license")) {
                return true;
            }
        }
        return false;
    }

    private final AgentProfileRepository agentProfileRepository;
    private final CommissionPlanRepository commissionPlanRepository;
    private final CommissionRuleRepository commissionRuleRepository;
    private final CommissionStatementRepository commissionStatementRepository;
    private final CommissionAccrualRepository commissionAccrualRepository;
    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final ApplicationEventPublisher eventPublisher;

    public DistributionApiImpl(AgentProfileRepository agentProfileRepository,
                                CommissionPlanRepository commissionPlanRepository,
                                CommissionRuleRepository commissionRuleRepository,
                                CommissionStatementRepository commissionStatementRepository,
                                CommissionAccrualRepository commissionAccrualRepository,
                                PartyApi partyApi, ProductApi productApi,
                                ApplicationEventPublisher eventPublisher) {
        this.agentProfileRepository = agentProfileRepository;
        this.commissionPlanRepository = commissionPlanRepository;
        this.commissionRuleRepository = commissionRuleRepository;
        this.commissionStatementRepository = commissionStatementRepository;
        this.commissionAccrualRepository = commissionAccrualRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public AgentView onboardAgent(OnboardAgentRequest request, String onboardedBy) {
        UUID tenantId = TenantContext.get();

        // 1. Party must exist AND be KYC VERIFIED. openapi-distribution.yaml declares a 422 for
        //    exactly this ("Referenced party does not have KYC status VERIFIED").
        //    PartyNotFoundException propagates as-is (404 at the boundary).
        PartyView party = partyApi.getParty(request.partyId());
        if (party.kycStatus() != KycStatus.VERIFIED) {
            throw new DistributionValidationException("Party " + request.partyId()
                + " has KYC status " + party.kycStatus() + "; an agent must be VERIFIED to onboard");
        }

        // 2. A hierarchy parent, if given, must exist in THIS tenant. Not optional rigour: an
        //    unchecked parent id would silently create an orphan branch that the override walk
        //    then cannot resolve, and the FK alone would not catch a cross-tenant id because RLS
        //    hides the row rather than rejecting the reference.
        if (request.hierarchyParentId() != null) {
            agentProfileRepository.findByAgentIdAndTenantId(request.hierarchyParentId(), tenantId)
                .orElseThrow(() -> new DistributionValidationException(
                    "Hierarchy parent " + request.hierarchyParentId() + " does not exist in this tenant"));
        }

        // 3. A licence expiring in the past is a data-entry error, not a valid onboarding.
        if (!request.licenseExpiryDate().isAfter(LocalDate.now())) {
            throw new DistributionValidationException(
                "License expiry " + request.licenseExpiryDate() + " is not in the future");
        }

        // 4. Length is checked HERE rather than left to the column, because
        //    agent_profile.license_number is VARCHAR(50) and an over-long value would otherwise
        //    reach the database as a DataIntegrityViolationException -- which the catch below
        //    used to report, wrongly, as "already in use". Matches openapi-distribution.yaml's
        //    declared maxLength.
        if (request.licenseNumber() == null || request.licenseNumber().isBlank()) {
            throw new DistributionValidationException("A license number is required");
        }
        if (request.licenseNumber().length() > MAX_LICENSE_NUMBER_LENGTH) {
            throw new DistributionValidationException("License number is " + request.licenseNumber().length()
                + " characters; the maximum is " + MAX_LICENSE_NUMBER_LENGTH);
        }

        AgentProfile agent = new AgentProfile(tenantId, request.partyId(), request.licenseNumber(),
            request.licenseExpiryDate(), request.hierarchyParentId(), null, onboardedBy);

        // The unique index ux_agent_license (tenant_id, license_number) means a duplicate licence
        // throws DataIntegrityViolationException -- caught and rethrown as a validation failure so
        // the boundary maps a 422, not a 500. saveAndFlush forces the constraint check now, inside
        // this method, rather than deferring it to some later, harder-to-attribute flush point.
        // Nothing else is persisted in this transaction before or after, so -- unlike
        // ClaimsApiImpl.registerClaim's REQUIRES_NEW dance -- there is no further statement that
        // could hit "current transaction is aborted": this method simply lets the converted
        // exception propagate and the (empty) transaction rolls back.
        try {
            agentProfileRepository.saveAndFlush(agent);
        } catch (DataIntegrityViolationException e) {
            // Narrowed to the licence index specifically. This catch used to convert EVERY
            // integrity violation into "License number '...' is already in use", which meant a
            // value-too-long, a NOT NULL violation or a bad FK all reported a duplicate that did
            // not exist -- a 422 with a confidently false explanation, and a genuinely misleading
            // one to debug (found while writing the contract test, where an over-long licence
            // number reported itself as a duplicate of a UUID nothing else had ever used).
            // Anything that is NOT the licence collision rethrows and surfaces honestly.
            if (mentionsLicenseIndex(e)) {
                throw new DistributionValidationException(
                    "License number '" + request.licenseNumber() + "' is already in use in this tenant");
            }
            throw e;
        }

        // Matches api/asyncapi-events.yaml's AgentOnboardedPayload field-for-field.
        eventPublisher.publishEvent(DomainEventEnvelope.of("distribution.AgentOnboarded", tenantId,
            Map.of("agentId", agent.getAgentId(), "partyId", agent.getPartyId(),
                   "licenseNumber", agent.getLicenseNumber())));

        return toAgentView(agent);
    }

    @Override
    public AgentView getAgent(UUID agentId) {
        return toAgentView(findAgentOrThrow(agentId, TenantContext.get()));
    }

    /**
     * M13. Branches on which filters are present rather than building a single
     * do-everything query, mirroring PolicyController's structure -- a JPQL method
     * only where `q` needs a LIKE, derived queries otherwise.
     */
    @Override
    public Page<AgentView> listAgents(String q, LicenseStatus status, UUID partyId, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<AgentProfile> page;
        if (partyId != null) {
            // Reuses the lookup resolveAgentTeam already relies on rather than adding a paged query
            // for it: a party holds at most a handful of agent records, so paging in memory over
            // that list is honest about the size instead of pretending it needs a database page.
            // The other filters are ignored on this branch on purpose -- "is this party an agent"
            // is a different question from "search the agent register", and combining them would
            // invite a caller to think it had searched when it had not.
            List<AgentProfile> profiles = agentProfileRepository.findByTenantIdAndPartyId(tenantId, partyId);
            page = new PageImpl<>(profiles, pageable, profiles.size());
        } else if (q != null && !q.isBlank()) {
            page = agentProfileRepository.search(tenantId, q.trim(), status, pageable);
        } else if (status != null) {
            page = agentProfileRepository.findByTenantIdAndLicenseStatus(tenantId, status, pageable);
        } else {
            page = agentProfileRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(this::toAgentView);
    }

    @Override
    public List<UUID> resolveAgentTeam(UUID partyId) {
        UUID tenantId = TenantContext.get();
        List<AgentProfile> ownProfiles = agentProfileRepository.findByTenantIdAndPartyId(tenantId, partyId);
        if (ownProfiles.isEmpty()) {
            return List.of();
        }
        // A LinkedHashSet, not a List, because a party holding more than one AgentProfile (no DB
        // constraint prevents it -- see AgentController's own note on this) could otherwise walk
        // overlapping downlines and duplicate an id in the result.
        java.util.Set<UUID> team = new java.util.LinkedHashSet<>();
        java.util.function.Function<UUID, List<UUID>> childrenOf = id ->
            agentProfileRepository.findByTenantIdAndHierarchyParentId(tenantId, id).stream()
                .map(AgentProfile::getAgentId).toList();
        for (AgentProfile own : ownProfiles) {
            team.add(own.getAgentId());
            team.addAll(CommissionCalculator.resolveDescendantIds(own.getAgentId(), childrenOf));
        }
        return List.copyOf(team);
    }

    @Override
    @Transactional
    public AgentView suspendAgent(UUID agentId, String suspendedBy) {
        UUID tenantId = TenantContext.get();
        AgentProfile agent = findAgentOrThrow(agentId, tenantId);
        if (agent.getLicenseStatus() != LicenseStatus.ACTIVE) {
            throw new InvalidAgentStateException("Agent " + agentId
                + " must be ACTIVE to be SUSPENDED (current: " + agent.getLicenseStatus() + ")");
        }
        agent.setLicenseStatus(LicenseStatus.SUSPENDED);
        agent.setUpdatedAt(Instant.now());
        agent.setUpdatedBy(suspendedBy);
        agentProfileRepository.save(agent);
        eventPublisher.publishEvent(DomainEventEnvelope.of("distribution.AgentSuspended", tenantId,
            Map.of("agentId", agentId)));
        return toAgentView(agent);
    }

    @Override
    @Transactional
    public AgentView reactivateAgent(UUID agentId, String reactivatedBy) {
        UUID tenantId = TenantContext.get();
        AgentProfile agent = findAgentOrThrow(agentId, tenantId);
        if (agent.getLicenseStatus() != LicenseStatus.SUSPENDED) {
            throw new InvalidAgentStateException("Agent " + agentId
                + " must be SUSPENDED to be reactivated (current: " + agent.getLicenseStatus() + ")");
        }
        agent.setLicenseStatus(LicenseStatus.ACTIVE);
        agent.setUpdatedAt(Instant.now());
        agent.setUpdatedBy(reactivatedBy);
        agentProfileRepository.save(agent);
        eventPublisher.publishEvent(DomainEventEnvelope.of("distribution.AgentReactivated", tenantId,
            Map.of("agentId", agentId)));
        return toAgentView(agent);
    }

    @Override
    @Transactional
    public CommissionPlanView createCommissionPlan(UUID productId, List<CommissionRuleInput> rules, String createdBy) {
        UUID tenantId = TenantContext.get();

        // The product must exist. ProductNotFoundException propagates as-is (404 at the boundary).
        productApi.getActiveSnapshot(productId, LocalDate.now());

        if (rules == null || rules.isEmpty()) {
            throw new DistributionValidationException("A commission plan must have at least one rule");
        }
        for (CommissionRuleInput rule : rules) {
            validateRuleShape(rule);
        }

        CommissionPlan plan = new CommissionPlan(tenantId, productId, createdBy);
        commissionPlanRepository.save(plan);

        List<CommissionRule> savedRules = new ArrayList<>();
        for (CommissionRuleInput input : rules) {
            // CommissionRule's own constructor rejects THRESHOLD_BONUS outright (M7 user decision
            // 1) -- not re-checked here, that would be dead code duplicating that guard.
            CommissionRule rule = new CommissionRule(tenantId, plan.getCommissionPlanId(), input.tierType(),
                input.rate(), input.flatAmount(), input.flatCurrency(), null);
            savedRules.add(commissionRuleRepository.save(rule));
        }

        return toCommissionPlanView(plan, savedRules);
    }

    /** Each rule must be rate-XOR-flat, with flatCurrency paired to flatAmount, and whichever of
     * the two is present must be positive -- so the domain rejects a malformed rule before V2's
     * own {@code commission_rule_rate_xor_flat}/{@code commission_rule_flat_currency_paired}/
     * {@code commission_rule_*_positive} CHECK constraints ever see it. {@link CommissionRule}'s
     * constructor deliberately does NOT re-validate this (see its own javadoc): this method is
     * where that validation lives. */
    private void validateRuleShape(CommissionRuleInput rule) {
        boolean hasRate = rule.rate() != null;
        boolean hasFlat = rule.flatAmount() != null;
        if (hasRate == hasFlat) {
            throw new DistributionValidationException(
                "Commission rule for tier " + rule.tierType() + " must specify exactly one of rate or "
                    + "flatAmount, not " + (hasRate ? "both" : "neither"));
        }
        if (hasRate && rule.rate().signum() <= 0) {
            throw new DistributionValidationException("Commission rule rate must be positive, got " + rule.rate());
        }
        if (hasFlat && rule.flatAmount().signum() <= 0) {
            throw new DistributionValidationException(
                "Commission rule flatAmount must be positive, got " + rule.flatAmount());
        }
        boolean hasFlatCurrency = rule.flatCurrency() != null && !rule.flatCurrency().isBlank();
        if (hasFlat != hasFlatCurrency) {
            throw new DistributionValidationException(
                "flatAmount and flatCurrency must be provided together for tier " + rule.tierType());
        }
    }

    /**
     * Di1's named method. Precedence below is INVENTED -- no Phase 0 document defines what
     * "applicable plan" means when both an agent-level assignment and a product-level ACTIVE plan
     * could apply -- and is documented here rather than guessed silently a second time:
     *
     * <ol>
     *   <li>If the agent has an assigned {@code commissionPlanId} that still resolves to a real
     *       plan row in this tenant, THAT plan wins outright -- even if it happens to belong to a
     *       different product than the {@code productId} argument. {@code AgentProfile} carries a
     *       single scalar {@code commissionPlanId}, not one per product, so there is no
     *       finer-grained agent-level assignment to prefer; this argument only ever narrows the
     *       fallback search below.</li>
     *   <li>Otherwise, fall back to the tenant's ACTIVE plan for the given {@code productId} (the
     *       first one found, if more than one somehow exists -- V1's schema places no uniqueness
     *       constraint on (tenant, product, ACTIVE)).</li>
     *   <li>If neither resolves, {@link CommissionPlanNotFoundException}.</li>
     * </ol>
     */
    @Override
    public CommissionPlanView getApplicablePlan(UUID agentId, UUID productId) {
        UUID tenantId = TenantContext.get();
        AgentProfile agent = findAgentOrThrow(agentId, tenantId);

        CommissionPlan plan = resolveApplicablePlan(tenantId, agent, productId);
        if (plan == null) {
            throw new CommissionPlanNotFoundException(
                "No commission plan applies to agent " + agentId + " for product " + productId);
        }
        return toCommissionPlanView(plan);
    }

    /** The precedence rule itself, shared by {@link #getApplicablePlan} (throws when nothing
     * resolves -- a human asked a direct question and deserves a 404) and {@link
     * #resolveAgentWithPlan} (returns null -- an event listener accruing commission must never
     * throw just because an agent has no plan configured; that is zero commission, not an error). */
    private CommissionPlan resolveApplicablePlan(UUID tenantId, AgentProfile agent, UUID productId) {
        CommissionPlan plan = null;
        if (agent.getCommissionPlanId() != null) {
            plan = commissionPlanRepository.findByCommissionPlanIdAndTenantId(agent.getCommissionPlanId(), tenantId)
                .orElse(null);
        }
        if (plan == null) {
            // findFirst() off a query that had no ORDER BY used to mean "whichever ACTIVE plan
            // Postgres returned first" -- see the repository method's javadoc. Now newest-first.
            plan = commissionPlanRepository
                .findByTenantIdAndProductIdAndStatusOrderByCreatedAtDescCommissionPlanIdDesc(
                    tenantId, productId, PlanStatus.ACTIVE)
                .stream().findFirst().orElse(null);
        }
        return plan;
    }

    /**
     * Task 6: resolves an agent plus the plan/rules {@link CommissionCalculator#calculate} needs,
     * for one leg of the hierarchy walk (the seller, or one ancestor). Returns {@code null} when
     * {@code agentId} does not resolve to a real row in this tenant -- defensive only, since every
     * id this is called with either came from {@code policy.PolicyIssued}'s own
     * {@code agentOfRecordId} (which {@code PolicyEventListener} already resolves the seller
     * against before calling this) or from walking {@code hierarchy_parent_id}, a self-FK into
     * this very table -- a vanished ancestor should not happen, but an event listener must never
     * throw for it. Package-private: {@code PolicyEventListener} is this method's only caller.
     */
    CommissionCalculator.AgentWithPlan resolveAgentWithPlan(UUID tenantId, UUID agentId, UUID productId) {
        AgentProfile agent = agentProfileRepository.findByAgentIdAndTenantId(agentId, tenantId).orElse(null);
        if (agent == null) {
            return null;
        }
        CommissionPlan plan = resolveApplicablePlan(tenantId, agent, productId);
        List<CommissionRule> rules = plan == null ? List.of()
            : commissionRuleRepository.findByCommissionPlanIdAndTenantId(plan.getCommissionPlanId(), tenantId);
        return new CommissionCalculator.AgentWithPlan(agent, plan, rules);
    }

    /**
     * Task 6's internal accrual primitive. Persists ONE {@link CommissionCalculator.Accrual} (an
     * issuance line item) or ONE clawback reversal (when {@code reversesAccrualId} is non-null)
     * into the agent's OPEN statement for {@code (period, currency)} -- creating that statement if
     * none exists yet -- then recomputes the statement's total from its own full accrual list and
     * saves it. Package-private: {@code PolicyEventListener} is this method's only caller, for
     * both the issuance and the clawback path (a reversal is just an accrual whose amount is
     * negative and whose {@code reversesAccrualId} is set -- same persistence shape, per {@link
     * CommissionAccrual}'s own javadoc on why a clawback is a new row, never a mutation).
     *
     * <p><b>Idempotent on redelivery.</b> The pre-check below is a fast, cheap skip; the real
     * backstop is the database itself -- {@code ux_commission_accrual_once} for an issuance line
     * ({@code reversesAccrualId == null}) or {@code ux_commission_accrual_single_reversal} for a
     * reversal -- caught here as {@link DataIntegrityViolationException} and treated identically
     * to the pre-check finding a duplicate: {@link Optional#empty()}, no statement mutated, no
     * event for the caller to publish.
     *
     * <p>Never called for a statement this method itself finds to be {@code PAID}: the caller
     * (clawback path) always targets the CURRENTLY OPEN period, not the period of the accrual
     * being reversed, so a paid statement is never the one this method writes into -- see
     * {@code PolicyEventListener.handlePolicyLapsed}'s own javadoc for why that invariant holds
     * rather than being re-checked here.
     *
     * @return the persisted accrual, or empty if this exact accrual (or reversal) already exists
     */
    Optional<CommissionAccrual> persistAccrual(UUID tenantId, UUID agentId, String policyNumber, TierType tierType,
                                                BigDecimal amount, String currency, String period, String sourceRef,
                                                UUID reversesAccrualId, String createdBy) {
        boolean alreadyAccrued = reversesAccrualId == null
            ? commissionAccrualRepository.existsByTenantIdAndAgentIdAndTierTypeAndSourceRefAndReversesAccrualIdIsNull(
                  tenantId, agentId, tierType, sourceRef)
            : commissionAccrualRepository.existsByTenantIdAndReversesAccrualId(tenantId, reversesAccrualId);
        if (alreadyAccrued) {
            return Optional.empty();
        }

        CommissionStatement statement = getOrCreateOpenStatement(tenantId, agentId, period, currency);
        CommissionAccrual accrual = new CommissionAccrual(tenantId, agentId, policyNumber, tierType, amount,
            currency, period, sourceRef, reversesAccrualId, createdBy);
        accrual.attachToStatement(statement.getStatementId());
        try {
            commissionAccrualRepository.saveAndFlush(accrual);
        } catch (DataIntegrityViolationException e) {
            // The pre-check above is not the guarantee under concurrent redelivery -- the unique
            // index is. Same idempotent-skip outcome either way.
            return Optional.empty();
        }

        List<CommissionAccrual> lineItems = commissionAccrualRepository
            .findByStatementIdAndTenantId(statement.getStatementId(), tenantId);
        statement.recomputeTotal(lineItems);
        commissionStatementRepository.save(statement);
        return Optional.of(accrual);
    }

    /** Finds the agent's existing statement for this exact (period, currency) -- {@code
     * ux_commission_statement_identity} guarantees at most one -- or creates a fresh OPEN one.
     * Never returns a statement for a DIFFERENT period than asked: the clawback path relies on
     * this to land a reversal in today's period even when the accrual being reversed lived in an
     * older, possibly since-PAID, statement. */
    private CommissionStatement getOrCreateOpenStatement(UUID tenantId, UUID agentId, String period, String currency) {
        return commissionStatementRepository.findByTenantIdAndAgentIdAndPeriodAndTotalCurrency(tenantId, agentId, period, currency)
            .orElseGet(() -> commissionStatementRepository.save(new CommissionStatement(tenantId, agentId, period, currency)));
    }

    @Override
    public List<CommissionStatementView> listStatements(UUID agentId, String period) {
        UUID tenantId = TenantContext.get();
        // Confirms the agent exists and belongs to this tenant first -- otherwise an
        // unknown/cross-tenant agentId would silently return an empty list instead of 404ing,
        // mirroring ClaimsApiImpl.listEvidence's own precedent for a "list children of X" read.
        findAgentOrThrow(agentId, tenantId);

        List<CommissionStatement> statements =
            commissionStatementRepository.findByTenantIdAndAgentIdOrderByPeriodDesc(tenantId, agentId);
        if (period != null && !period.isBlank()) {
            statements = statements.stream().filter(s -> period.equals(s.getPeriod())).toList();
        }
        return statements.stream().map(this::toStatementView).toList();
    }

    @Override
    public List<CommissionAccrualView> listAccruals(UUID statementId) {
        UUID tenantId = TenantContext.get();
        // Confirms the statement exists and belongs to this tenant first, same reasoning as
        // listStatements above.
        commissionStatementRepository.findByStatementIdAndTenantId(statementId, tenantId)
            .orElseThrow(() -> new CommissionStatementNotFoundException("Commission statement " + statementId + " not found"));

        return commissionAccrualRepository.findByStatementIdAndTenantId(statementId, tenantId).stream()
            .map(this::toAccrualView)
            .toList();
    }

    @Override
    @Transactional
    public void requestStatementPayout(UUID statementId, String payeeRef, String idempotencyKey, String requestedBy) {
        UUID tenantId = TenantContext.get();
        CommissionStatement statement = commissionStatementRepository.findByStatementIdAndTenantId(statementId, tenantId)
            .orElseThrow(() -> new CommissionStatementNotFoundException("Commission statement " + statementId + " not found"));

        // Validates non-blank payeeRef/idempotencyKey (and a positive total) BEFORE any event is
        // published -- a blank key forwarded to `payment` is swallowed inside its AFTER_COMMIT
        // listener and looks like a request that reached the rail zero times. This also performs
        // the CLOSED/PAYOUT_FAILED -> PAYOUT_REQUESTED transition itself.
        statement.markPayoutRequested(idempotencyKey, payeeRef);
        commissionStatementRepository.save(statement);

        // Matches api/asyncapi-events.yaml's CommissionPayoutRequestedPayload field-for-field.
        // Carries statementId, not sourceRef -- `payment` derives sourceRef = statementId.toString()
        // itself (Task 8). LinkedHashMap, not Map.of: amount is itself a nested map built from
        // Map.of, which is fine, but using LinkedHashMap at the top level here costs nothing and
        // keeps this method robust if a future field here is ever allowed to be null.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statementId", statementId);
        payload.put("payeeRef", payeeRef);
        payload.put("amount", Map.of("amount", statement.getTotalAmount().toPlainString(),
                                      "currencyCode", statement.getTotalCurrency()));
        payload.put("idempotencyKey", idempotencyKey);
        eventPublisher.publishEvent(DomainEventEnvelope.of("distribution.CommissionPayoutRequested", tenantId, payload));
    }

    private AgentProfile findAgentOrThrow(UUID agentId, UUID tenantId) {
        return agentProfileRepository.findByAgentIdAndTenantId(agentId, tenantId)
            .orElseThrow(() -> new AgentNotFoundException("Agent " + agentId + " not found"));
    }

    private AgentView toAgentView(AgentProfile agent) {
        return new AgentView(agent.getAgentId(), agent.getPartyId(), agent.getLicenseNumber(),
            agent.getLicenseStatus(), agent.getLicenseExpiryDate(), agent.getHierarchyParentId(),
            agent.getCommissionPlanId());
    }

    private CommissionPlanView toCommissionPlanView(CommissionPlan plan) {
        List<CommissionRule> rules = commissionRuleRepository.findByCommissionPlanIdAndTenantId(
            plan.getCommissionPlanId(), plan.getTenantId());
        return toCommissionPlanView(plan, rules);
    }

    private CommissionPlanView toCommissionPlanView(CommissionPlan plan, List<CommissionRule> rules) {
        List<CommissionRuleView> ruleViews = rules.stream().map(this::toCommissionRuleView).toList();
        return new CommissionPlanView(plan.getCommissionPlanId(), plan.getProductId(), plan.getStatus(), ruleViews);
    }

    private CommissionRuleView toCommissionRuleView(CommissionRule rule) {
        return new CommissionRuleView(rule.getCommissionRuleId(), rule.getTierType(), rule.getRate(),
            rule.getFlatAmount(), rule.getFlatCurrency());
    }

    private CommissionStatementView toStatementView(CommissionStatement statement) {
        return new CommissionStatementView(statement.getStatementId(), statement.getAgentId(), statement.getPeriod(),
            statement.getTotalAmount(), statement.getTotalCurrency(), statement.getStatus(),
            statement.getClosedAt(), statement.getPaidAt());
    }

    private CommissionAccrualView toAccrualView(CommissionAccrual accrual) {
        return new CommissionAccrualView(accrual.getAccrualId(), accrual.getAgentId(), accrual.getStatementId(),
            accrual.getPolicyNumber(), accrual.getTierType(), accrual.getAmount(), accrual.getCurrency(),
            accrual.getPeriod(), accrual.getSourceRef(), accrual.getReversesAccrualId());
    }
}
