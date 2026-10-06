package tz.co.nlolo.lifeplatform.distribution.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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

    /**
     * {@code salesChannel} null means AGENT; {@code homeBranch} is a refdata BRANCH code, null only for a caller that
     * does not know one (IFRS 17 I2). The console always names both.
     */
    record OnboardAgentRequest(UUID partyId, String licenseNumber, LocalDate licenseExpiryDate,
                                UUID hierarchyParentId, SalesChannel salesChannel, String homeBranch) {
        public OnboardAgentRequest(UUID partyId, String licenseNumber, LocalDate licenseExpiryDate, UUID hierarchyParentId) {
            this(partyId, licenseNumber, licenseExpiryDate, hierarchyParentId, null, null);
        }
    }
    record CommissionRuleInput(TierType tierType, BigDecimal rate,
                                BigDecimal flatAmount, String flatCurrency) {}

    AgentView onboardAgent(OnboardAgentRequest request, String onboardedBy);
    AgentView getAgent(UUID agentId);

    /**
     * Change the channel an agent sells through and the branch it sells from (IFRS 17 I2). Cases opened afterwards
     * take the new defaults; a case already opened keeps its own, and an issued policy never changes.
     */
    AgentView updateAgentPlacement(UUID agentId, SalesChannel salesChannel, String homeBranch, String updatedBy);

    /**
     * "Is this id an agent in this tenant" — the question, asked without an exception.
     *
     * <p>{@link #getAgent} throws {@code AgentNotFoundException}, which is right for a caller
     * addressing an agent it expects to exist. Its callers here are validating a SUBMITTED
     * FIELD (a case's or an issue request's {@code agentOfRecordId}) where "not an agent" is an
     * ordinary answer to be turned into a 422, not an exceptional one — and catching an
     * exception to express a boolean puts control flow in a catch block in two modules.
     *
     * <p>Empty for an agent in another tenant too: the lookup is tenant-scoped and RLS is the
     * backstop, so a cross-tenant id is simply not an agent here.
     */
    Optional<AgentView> getAgentIfPresent(UUID agentId);

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
    /**
     * @param partyId narrows to the agent record(s) belonging to one party, answering "is this
     *                client also an agent" for the client register. Null means no filter. A party
     *                may hold more than one agent record, which is why this is a filter on a list
     *                rather than a lookup returning one.
     */
    org.springframework.data.domain.Page<AgentView> listAgents(
        String q, LicenseStatus status, UUID partyId, org.springframework.data.domain.Pageable pageable);

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

    /**
     * The agent this party IS, if any — "who is this person, as an agent".
     *
     * <p>Built for {@code policy}, which at issuance reads the policyholder's registering agent
     * (recorded on the party record as a party id, because party may not depend on distribution)
     * and has to turn it into the agent of record. That is what makes an agent's commission real:
     * commission accrues off {@code PolicyActivated.agentOfRecordId}, and before this the only
     * record of who registered a client was a Keycloak subject nothing could resolve.
     *
     * <p>Distinct from {@link #resolveAgentTeam}, which answers "whose business may this caller
     * SEE" and deliberately includes the hierarchy beneath them. This is one agent, and it is
     * about who gets paid.
     *
     * <p>Prefers an ACTIVE profile and otherwise returns the newest, matching how
     * {@code /agents/me} resolves the same question. A suspended agent still earns on business
     * they brought in — suspension stops them selling, and silently redirecting their commission
     * to nobody would be a money decision taken by a null check.
     *
     * <p><b>Deterministic, and it has to be.</b> A party may hold several agent profiles, and
     * this method decides WHO GETS PAID. The underlying query orders newest-first; before it did,
     * the answer was whichever row Postgres returned first and two identical calls could credit
     * two different agents.
     *
     * @return empty when this party is not an agent in this tenant, which is the ordinary case:
     *     most parties are customers.
     */
    Optional<UUID> agentIdForParty(UUID partyId);

    /** ACTIVE -> SUSPENDED. {@code AgentProfile.setLicenseStatus} has existed since M7 with no
     * caller anywhere on the platform -- this is the first one. @throws InvalidAgentStateException
     * if the agent is not currently ACTIVE. */
    AgentView suspendAgent(UUID agentId, String suspendedBy);

    /** SUSPENDED -> ACTIVE. @throws InvalidAgentStateException if the agent is not currently
     * SUSPENDED -- in particular, an EXPIRED agent is not reactivated through this method, since
     * expiry is calendar-driven, not a staff decision to undo. */
    AgentView reactivateAgent(UUID agentId, String reactivatedBy);

    CommissionPlanView createCommissionPlan(UUID productId, List<CommissionRuleInput> rules, String createdBy);

    /**
     * The commission rate ONE agent earns, as a percentage of premium -- a new plan with a single
     * FIRST_YEAR rule, attached to the agent so it wins over any product-wide plan.
     *
     * <p>For credit life, where the rate is agreed per lender (client answer 3.1: "adjustable per
     * lender") and a single premium means FIRST_YEAR fires once per file and RENEWAL never.
     * Nothing could attach a plan to an agent before: the lookup honoured an agent's own plan
     * and no path ever set one.
     *
     * <p>Replacing a rate creates a new plan and repoints the agent; the old plan stays, so what
     * earlier accruals were calculated under can still be read. Forward only, like every
     * commission change: nothing already accrued is recalculated.
     *
     * <p>An agent's own plan applies to whatever they sell. A lender sells its own scheme, so
     * that is the scheme's rate; an agent selling several products would need product rules.
     *
     * @param ratePercent greater than 0 and at most 100, to two decimal places (12.5 is 12.5%)
     */
    CommissionPlanView setAgentCommissionRate(UUID agentId, UUID productId, BigDecimal ratePercent, String setBy);

    /** Every accrual and clawback booked on one policy, oldest first -- what it has earned whom. */
    List<CommissionAccrualView> listAccrualsForPolicy(String policyNumber);
    CommissionPlanView getApplicablePlan(UUID agentId, UUID productId);

    List<CommissionStatementView> listStatements(UUID agentId, String period);
    List<CommissionAccrualView> listAccruals(UUID statementId);

    /** Staff-triggered payout for a CLOSED (or previously PAYOUT_FAILED) statement. Publishes
     * distribution.CommissionPayoutRequested; settlement completes asynchronously. */
    void requestStatementPayout(UUID statementId, String payeeRef, String idempotencyKey, String requestedBy);
}
