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

    /**
     * The policy issued from a given underwriting case, if any.
     *
     * <p>There was no finder on this column at all, which is why nothing could answer "has this
     * case already been issued?" -- the question whose absence let one application become two
     * contracts. {@code ux_policy_underwriting_case} guarantees at most one row comes back.
     *
     * <p>Tenant-scoped rather than leaning on row-level security alone, matching every other
     * finder here: this one gates whether a contract may be issued.
     */
    Optional<Policy> findByTenantIdAndUnderwritingCaseId(UUID tenantId, UUID underwritingCaseId);
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
     *
     * <p><b>{@code relatedPartyId} is a THREE-WAY match and is not the same filter as
     * {@code policyholderPartyId}.</b> It asks "which policies is this person connected to in
     * any capacity the platform records" -- as the owner, as the life assured, or as a named
     * beneficiary -- and it exists because the claims desk needs exactly that question. A
     * claimant is very often NOT the policyholder: on a death claim the life assured is the
     * deceased and the claimant is usually a beneficiary, so filtering by
     * {@code policyholderPartyId} would return nothing for the commonest claim there is.
     *
     * <p>The two filters AND together rather than merging, deliberately. A customer-realm caller
     * is force-scoped through {@code policyholderPartyId} ({@code PolicyController.searchPolicies}),
     * and that scoping must keep meaning exactly what it means today -- so a widening
     * interpretation of {@code relatedPartyId} must never be able to reach past it.
     *
     * <p>The beneficiary leg matches only {@code active = true} rows, matching what
     * {@code PolicyApiImpl.toView} actually returns: a beneficiary who has since been replaced
     * is not a person to offer a claim form to.
     */
    @Query("SELECT p FROM Policy p WHERE p.tenantId = :tenantId "
        + "AND (:policyholderPartyId IS NULL OR p.policyholderPartyId = :policyholderPartyId) "
        + "AND (:relatedPartyId IS NULL "
        + "     OR p.policyholderPartyId = :relatedPartyId "
        + "     OR p.lifeAssuredPartyId = :relatedPartyId "
        + "     OR EXISTS (SELECT 1 FROM Beneficiary b WHERE b.tenantId = p.tenantId "
        + "                AND b.policyNumber = p.policyNumber AND b.partyId = :relatedPartyId "
        + "                AND b.active = true)) "
        + "AND (:status IS NULL OR p.status = :status) "
        + "AND (:agentOfRecordIds IS NULL OR p.agentOfRecordId IN :agentOfRecordIds) "
        + "AND (:q IS NULL OR LOWER(p.policyNumber) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))")
    Page<Policy> search(@Param("tenantId") UUID tenantId,
                         @Param("policyholderPartyId") UUID policyholderPartyId,
                         @Param("relatedPartyId") UUID relatedPartyId,
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
