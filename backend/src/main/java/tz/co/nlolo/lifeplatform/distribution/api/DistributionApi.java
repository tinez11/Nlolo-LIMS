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
