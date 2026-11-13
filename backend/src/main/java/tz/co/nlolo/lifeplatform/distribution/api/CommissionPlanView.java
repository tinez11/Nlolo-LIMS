package tz.co.nlolo.lifeplatform.distribution.api;

import java.util.List;
import java.util.UUID;

/** Read view of {@code distribution.domain.CommissionPlan} plus its rules, matching
 * openapi-distribution.yaml's CommissionPlanView shape (planId/productId/rules) with
 * {@code status} additionally exposed -- Task 9/10 and this module's own tests need to assert on
 * which plan (ACTIVE vs RETIRED) was actually resolved by {@link DistributionApi#getApplicablePlan}. */
public record CommissionPlanView(UUID commissionPlanId, UUID productId, PlanStatus status,
                                  List<CommissionRuleView> rules,
                                  /* 2026-11-12, finding 190: who proposed it, whose rate it is, and who decided. */
                                  String proposedBy, UUID proposedForAgentId, String decidedBy, String decisionReason,
                                  /* When it was proposed; the product's and the agent's names while it waits. */
                                  java.time.Instant proposedAt, String productName, String agentName) {

    /** The shape before the approval step: a plan already in force. */
    public CommissionPlanView(UUID commissionPlanId, UUID productId, PlanStatus status, List<CommissionRuleView> rules) {
        this(commissionPlanId, productId, status, rules, null, null, null, null, null, null, null);
    }
}
