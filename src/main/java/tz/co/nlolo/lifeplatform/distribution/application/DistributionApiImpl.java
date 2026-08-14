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
import tz.co.nlolo.lifeplatform.distribution.api.PlanStatus;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 5: {@link DistributionApi}'s full published surface -- agent onboarding, commission-plan
 * authoring, and the reads Task 9's REST layer exposes. Consumption/accrual of commission (Task
 * 6/7's {@code PolicyEventListener}, walking the hierarchy via {@code CommissionCalculator}) is
 * out of scope here; this class only ever reads {@code CommissionStatement}/{@code
 * CommissionAccrual} rows, it never creates them.
 *
 * <p>{@link CommissionStatementNotFoundException} (review fix) is the third named not-found type
 * alongside {@link AgentNotFoundException} and {@link CommissionPlanNotFoundException} -- every
 * lookup-by-id in this class that can miss has its own dedicated, HTTP-mappable exception, since
 * this module's whole purpose is to be Task 9's REST surface: a bare JDK exception here would
 * silently 500 instead of 404 unless that task happened to remember to add a handler for it.
 */
@Service
public class DistributionApiImpl implements DistributionApi {

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
            throw new DistributionValidationException(
                "License number '" + request.licenseNumber() + "' is already in use in this tenant");
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

        CommissionPlan plan = null;
        if (agent.getCommissionPlanId() != null) {
            plan = commissionPlanRepository.findByCommissionPlanIdAndTenantId(agent.getCommissionPlanId(), tenantId)
                .orElse(null);
        }
        if (plan == null) {
            plan = commissionPlanRepository.findByTenantIdAndProductIdAndStatus(tenantId, productId, PlanStatus.ACTIVE)
                .stream().findFirst().orElse(null);
        }
        if (plan == null) {
            throw new CommissionPlanNotFoundException(
                "No commission plan applies to agent " + agentId + " for product " + productId);
        }
        return toCommissionPlanView(plan);
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
