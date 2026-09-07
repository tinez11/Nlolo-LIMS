package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.policy.api.MemberUnderwritingStatus;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyMember;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PolicyMemberRepository extends JpaRepository<PolicyMember, UUID> {

    /**
     * One page of a scheme's members.
     *
     * <p>The caller supplies the sort and MUST make it total. A bulk schedule upload gives
     * every row the same {@code joinedOn}, so that column alone leaves ties, and paging an
     * unordered query can show one member twice while never showing another. On a 500-life
     * scheme that is a member silently missing from the roll -- the same defect shape
     * PLAN.md section 10 records finding four times, once deciding an agent's commission
     * rate.
     */
    Page<PolicyMember> findByTenantIdAndPolicyNumberAndStatus(
        UUID tenantId, String policyNumber, String status, Pageable pageable);

    /**
     * As above, with a null {@code status} meaning "every member, including those who left".
     *
     * <p>{@code memberPartyIds} is the name search, resolved to ids by the party module before
     * it gets here — a member row holds no name of its own. Null means "no name filter"; the
     * caller must never pass an EMPTY collection, because that is both a Postgres syntax error
     * (`in ()`) and the opposite of what it looks like. See {@code PolicyApiImpl.listMembers},
     * which short-circuits to an empty page instead.
     */
    @Query("""
        select m from PolicyMember m
         where m.tenantId = :tenantId and m.policyNumber = :policyNumber
           and (:status is null or m.status = :status)
           and (:memberPartyIds is null or m.memberPartyId in :memberPartyIds)
        """)
    Page<PolicyMember> findMembers(@Param("tenantId") UUID tenantId,
                                    @Param("policyNumber") String policyNumber,
                                    @Param("status") String status,
                                    @Param("memberPartyIds") Collection<UUID> memberPartyIds,
                                    Pageable pageable);

    Optional<PolicyMember> findByPolicyMemberIdAndTenantId(UUID policyMemberId, UUID tenantId);

    /** Backs the "already on this scheme" check behind ux_policy_member_active. */
    boolean existsByTenantIdAndPolicyNumberAndMemberPartyIdAndStatus(
        UUID tenantId, String policyNumber, UUID memberPartyId, String status);

    long countByTenantIdAndPolicyNumberAndStatus(UUID tenantId, String policyNumber, String status);

    /**
     * Active members whose benefit exceeds the free cover limit with underwriting still
     * outstanding -- the queue a scheme administrator works, surfaced as a count so nobody
     * has to find them by eye down a 500-row schedule.
     */
    long countByTenantIdAndPolicyNumberAndStatusAndUnderwritingStatus(
        UUID tenantId, String policyNumber, String status, MemberUnderwritingStatus underwritingStatus);
}
