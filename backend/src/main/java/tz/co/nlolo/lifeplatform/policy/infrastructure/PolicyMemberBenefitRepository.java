package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyMemberBenefit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PolicyMemberBenefitRepository extends JpaRepository<PolicyMemberBenefit, UUID> {

    /**
     * The benefit in force for one member as at a date -- the row a claim must be paid on.
     *
     * <p>Take {@code .getFirst()} of the result with a {@code Pageable} of one. Ordered
     * {@code effectiveFrom} DESC with the id as a tie-breaker so the order is TOTAL:
     * UNIQUE (member, effective_from) makes ties impossible today, and the tie-breaker
     * keeps "which row applies" answerable if that constraint is ever relaxed. Resolving
     * one row out of many without a total order is the single most reliably-wrong piece of
     * logic on this platform.
     */
    @Query("""
        select b from PolicyMemberBenefit b
         where b.policyMemberId = :memberId and b.tenantId = :tenantId
           and b.effectiveFrom <= :asOf
         order by b.effectiveFrom desc, b.policyMemberBenefitId desc
        """)
    List<PolicyMemberBenefit> findInForce(@Param("memberId") UUID policyMemberId,
                                           @Param("tenantId") UUID tenantId,
                                           @Param("asOf") LocalDate asOf,
                                           Pageable pageable);

    /** Every benefit row for a member, newest first -- the salary and cover history. */
    List<PolicyMemberBenefit> findByTenantIdAndPolicyMemberIdOrderByEffectiveFromDesc(
        UUID tenantId, UUID policyMemberId);

    /**
     * The scheme's total sum insured: what every ACTIVE member is actually covered for,
     * taking each member's benefit in force as at the date.
     *
     * <p><b>Derived, not stored.</b> A total kept on the scheme drifts the moment somebody
     * joins mid-month, and a figure that can disagree with its inputs is how this platform
     * has repeatedly ended up paying the wrong amount. Native SQL because the LATERAL
     * "latest row per member" is not expressible in JPQL.
     *
     * <p>Returns 0, never null, for a scheme with no active members.
     */
    @Query(value = """
        select coalesce(sum(latest.covered_amount), 0)
          from policy.policy_member m
          join lateral (
                select b.covered_amount
                  from policy.policy_member_benefit b
                 where b.policy_member_id = m.policy_member_id
                   and b.effective_from <= :asOf
                 order by b.effective_from desc, b.policy_member_benefit_id desc
                 limit 1
               ) latest on true
         where m.tenant_id = :tenantId
           and m.policy_number = :policyNumber
           and m.status = 'ACTIVE'
        """, nativeQuery = true)
    BigDecimal totalCovered(@Param("tenantId") UUID tenantId,
                             @Param("policyNumber") String policyNumber,
                             @Param("asOf") LocalDate asOf);

    /** The in-force benefit for one member, as a flat row. */
    interface InForceBenefitRow {
        UUID getPolicyMemberId();
        LocalDate getEffectiveFrom();
        BigDecimal getSalaryAmount();
        BigDecimal getBenefitAmount();
        BigDecimal getCoveredAmount();
    }

    /**
     * The in-force benefit for a whole page of members in ONE query.
     *
     * <p>Calling {@link #findInForce} per row would be 25 queries to draw one page of a
     * 500-life schedule, and 500 to draw the schedule. {@code DISTINCT ON} is Postgres's
     * "first row per group" and takes the same total ordering {@code findInForce} uses, so
     * the two cannot disagree about which row is in force.
     *
     * <p>Aliases are double-quoted so Postgres preserves their case: unquoted, it folds
     * them to lowercase and the projection silently finds no property to bind.
     *
     * <p><b>Never call with an empty {@code memberIds}</b> -- {@code in ()} is a Postgres
     * syntax error. The caller skips the query when its page is empty.
     */
    @Query(value = """
        select distinct on (b.policy_member_id)
               b.policy_member_id as "policyMemberId",
               b.effective_from   as "effectiveFrom",
               b.salary_amount    as "salaryAmount",
               b.benefit_amount   as "benefitAmount",
               b.covered_amount   as "coveredAmount"
          from policy.policy_member_benefit b
         where b.tenant_id = :tenantId
           and b.policy_member_id in (:memberIds)
           and b.effective_from <= :asOf
         order by b.policy_member_id, b.effective_from desc, b.policy_member_benefit_id desc
        """, nativeQuery = true)
    List<InForceBenefitRow> findInForceForMembers(@Param("tenantId") UUID tenantId,
                                                   @Param("memberIds") Collection<UUID> memberIds,
                                                   @Param("asOf") LocalDate asOf);
}
