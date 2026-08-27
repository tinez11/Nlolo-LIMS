package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.PlanStatus;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface CommissionPlanRepository extends JpaRepository<CommissionPlan, UUID> {
    Optional<CommissionPlan> findByCommissionPlanIdAndTenantId(UUID commissionPlanId, UUID tenantId);
    /**
     * {@code status} is a {@link PlanStatus} enum (Task 5 -- see {@link CommissionPlan}'s own
     * javadoc); passing the enum itself, never {@code status.name()}, matches this platform's
     * established rule for {@code @Enumerated(STRING)} derived-query parameters (see
     * {@code ClaimRepository}'s own javadoc on the same pitfall).
     *
     * <p><b>The ordering is load-bearing and was missing.</b> Its one caller,
     * {@code DistributionApiImpl.resolveApplicablePlan}, takes {@code .findFirst()} off this list
     * as the plan that applies to a product. Nothing supersedes a prior ACTIVE plan when a new one
     * is created, so two ACTIVE plans for the same product is a reachable state -- and with no
     * {@code ORDER BY} the one that won was whichever row Postgres happened to return first. That
     * is not a display glitch: this resolution decides the rate an agent is actually paid, and it
     * could differ between two identical calls.
     *
     * <p>Newest-first is the intended reading of "the current plan" and matches what the CRUD audit
     * prescribed when it recorded this defect. {@code commissionPlanId} makes the order total:
     * {@code createdAt} is assigned by {@code Instant.now()} in Java, so two plans authored in the
     * same instant would otherwise tie back into an undefined order.
     *
     * <p>This fixes only the non-deterministic pick. Superseding a prior ACTIVE plan on create, and
     * a way to deactivate one, remain deliberately deferred -- both need a real decision about what
     * happens to commission already accrued under the old plan.
     */
    List<CommissionPlan> findByTenantIdAndProductIdAndStatusOrderByCreatedAtDescCommissionPlanIdDesc(
        UUID tenantId, UUID productId, PlanStatus status);
}
