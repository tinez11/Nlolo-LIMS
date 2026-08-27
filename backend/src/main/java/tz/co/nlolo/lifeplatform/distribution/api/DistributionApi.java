package tz.co.nlolo.lifeplatform.distribution.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The `distribution` module's public surface: agent onboarding, commission-plan authoring
 * (staff/finance-only, Deliverable 3 Rev 2 §7.2), and the read paths an agent, supervisor or
 * staff member needs. Task 9 exposes this over HTTP (openapi-distribution.yaml); Task 10
 * contract-tests it; {@code omnichannel} already declares a dependency on {@code distribution::api}.
 *
 * <p>Consumption/accrual of commission (walking the hierarchy, applying {@code CommissionCalculator})
 * is Task 6/7's event-listener territory, not this interface -- everything here is either an
 * imperative staff/agent action or a plain read.
 */
public interface DistributionApi {

    record OnboardAgentRequest(UUID partyId, String licenseNumber, LocalDate licenseExpiryDate,
                                UUID hierarchyParentId) {}
    record CommissionRuleInput(TierType tierType, BigDecimal rate,
                                BigDecimal flatAmount, String flatCurrency) {}

    AgentView onboardAgent(OnboardAgentRequest request, String onboardedBy);
    AgentView getAgent(UUID agentId);

    /**
     * M13: the agents list, tenant-scoped and paged.
     *
     * Until now this module served every per-agent read but no list, so an agent
     * table had nothing to render and the console's nav item pointed at the
     * onboarding form instead of a queue.
     *
     * `q` matches LICENCE NUMBER. An agent has no name here -- the person's name
     * lives in `party`, reached through `partyId` -- and resolving it would make
     * distribution read another context's data to fill a column.
     */
    org.springframework.data.domain.Page<AgentView> listAgents(
        String q, LicenseStatus status, org.springframework.data.domain.Pageable pageable);

    /**
     * Resolves {@code partyId}'s own object-level read scope over agent-related data elsewhere on
     * the platform: its own agent record (if the party IS an agent in this tenant) plus everyone
     * within {@code MAX_HIERARCHY_WALK_DEPTH} levels below it in the hierarchy -- the same depth
     * cap {@code AgentController.enforceAgentReadAccess} already applies to reading a descendant's
     * own agent record, and the same one {@code CommissionCalculator}'s OVERRIDE/SUPERVISOR_OVERRIDE
     * tiers use. Empty if the party is not an agent in this tenant at all.
     *
     * <p>Built for {@code policy}/{@code claims} to scope an agents-realm token's "browse my book
     * of business" to what it is actually entitled to see -- see those modules' own controllers for
     * the previously-deferred gap this closes (their own comments named exactly this: "no
     * agent/agency data model... to resolve 'is this caller's agent identity the agentOfRecord'
     * against").
     */
    List<UUID> resolveAgentTeam(UUID partyId);

    /** ACTIVE -> SUSPENDED. {@code AgentProfile.setLicenseStatus} has existed since M7 with no
     * caller anywhere on the platform -- this is the first one. @throws InvalidAgentStateException
     * if the agent is not currently ACTIVE. */
    AgentView suspendAgent(UUID agentId, String suspendedBy);

    /** SUSPENDED -> ACTIVE. @throws InvalidAgentStateException if the agent is not currently
     * SUSPENDED -- in particular, an EXPIRED agent is not reactivated through this method, since
     * expiry is calendar-driven, not a staff decision to undo. */
    AgentView reactivateAgent(UUID agentId, String reactivatedBy);

    CommissionPlanView createCommissionPlan(UUID productId, List<CommissionRuleInput> rules, String createdBy);
    CommissionPlanView getApplicablePlan(UUID agentId, UUID productId);

    List<CommissionStatementView> listStatements(UUID agentId, String period);
    List<CommissionAccrualView> listAccruals(UUID statementId);

    /** Staff-triggered payout for a CLOSED (or previously PAYOUT_FAILED) statement. Publishes
     * distribution.CommissionPayoutRequested; settlement completes asynchronously. */
    void requestStatementPayout(UUID statementId, String payeeRef, String idempotencyKey, String requestedBy);
}
