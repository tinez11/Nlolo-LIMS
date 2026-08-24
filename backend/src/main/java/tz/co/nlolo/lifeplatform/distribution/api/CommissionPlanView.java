package tz.co.nlolo.lifeplatform.distribution.api;

import java.util.List;
import java.util.UUID;

/** Read view of {@code distribution.domain.CommissionPlan} plus its rules, matching
 * openapi-distribution.yaml's CommissionPlanView shape (planId/productId/rules) with
 * {@code status} additionally exposed -- Task 9/10 and this module's own tests need to assert on
 * which plan (ACTIVE vs RETIRED) was actually resolved by {@link DistributionApi#getApplicablePlan}. */
public record CommissionPlanView(UUID commissionPlanId, UUID productId, PlanStatus status,
                                  List<CommissionRuleView> rules) {}
