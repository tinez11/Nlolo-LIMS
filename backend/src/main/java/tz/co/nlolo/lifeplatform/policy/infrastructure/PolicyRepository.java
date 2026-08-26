package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.Policy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, String> {
    Optional<Policy> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);
    Page<Policy> findByTenantIdAndPolicyholderPartyId(UUID tenantId, UUID policyholderPartyId, Pageable pageable);
    Page<Policy> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
    Page<Policy> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Policy> findByTenantIdAndPolicyholderPartyIdAndStatus(UUID tenantId, UUID policyholderPartyId, String status, Pageable pageable);

    /**
     * The agents-realm-scoping counterpart to the four hand-rolled combinations above --
     * {@code agentOfRecordIds} is a THIRD optional dimension (policyholderPartyId x status x
     * agent scope), and branching that combinatorially would mean eight derived-query methods
     * for what one null-safe JPQL predicate expresses directly. {@code agentOfRecordIds} being
     * {@code null} means "no agent filter" (staff/customer callers); a caller-supplied set (an
     * agent's resolved hierarchy team, see {@code DistributionApi.resolveAgentTeam}) restricts
     * the result to policies sold by, or attributed to, someone in that team.
     */
    @Query("SELECT p FROM Policy p WHERE p.tenantId = :tenantId "
        + "AND (:policyholderPartyId IS NULL OR p.policyholderPartyId = :policyholderPartyId) "
        + "AND (:status IS NULL OR p.status = :status) "
        + "AND (:agentOfRecordIds IS NULL OR p.agentOfRecordId IN :agentOfRecordIds) "
        + "AND (:q IS NULL OR LOWER(p.policyNumber) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Policy> search(@Param("tenantId") UUID tenantId,
                         @Param("policyholderPartyId") UUID policyholderPartyId,
                         @Param("status") String status,
                         @Param("agentOfRecordIds") Collection<UUID> agentOfRecordIds,
                         @Param("q") String q,
                         Pageable pageable);

    /** Backs {@code claims}' own agent-team scoping (claims has no agentOfRecordId of its own --
     *  it joins through the policy it was filed against). A bare list, not paginated: this feeds
     *  an {@code IN (...)} filter on {@code claims.policyNumber}, not a response body. */
    @Query("SELECT p.policyNumber FROM Policy p WHERE p.tenantId = :tenantId AND p.agentOfRecordId IN :agentOfRecordIds")
    List<String> findPolicyNumbersByTenantIdAndAgentOfRecordIdIn(@Param("tenantId") UUID tenantId,
                                                                  @Param("agentOfRecordIds") Collection<UUID> agentOfRecordIds);
}
