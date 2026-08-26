package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard, per this project's
 * established convention (see any repository in payment or policyloan).
 *
 * <p>Task 10 review fix: the two {@code status}-filtering methods below take {@link ClaimStatus}
 * directly, NOT a {@code String} -- despite {@code ClaimsApiImpl.searchClaims}'s own (now-corrected)
 * comment having claimed a {@code String} was "the parameter type a derived-query method expects
 * against the enum-typed column, exactly as PolicyRepository.findByTenantIdAndStatus already does".
 * That claim was never actually exercised by any test (confirmed by searching the whole test tree:
 * zero callers ever passed a non-null status), and is FALSE against this codebase's real Hibernate
 * 6.5: Spring Data derives this query via the JPA Criteria API, whose parameter type is inferred
 * from the {@code status} ATTRIBUTE's Java type ({@link ClaimStatus}, {@code @Enumerated(STRING)}),
 * not from the repository method's own declared parameter type -- passing a {@code String} runtime
 * value throws {@code org.hibernate.query.QueryArgumentException: Argument [...] of type
 * [java.lang.String] did not match parameter type [ClaimStatus]} on EVERY call, unconditionally.
 * Declaring the parameter as {@link ClaimStatus} itself (letting Hibernate translate the enum to
 * its STRING column representation, standard JPA enum-parameter binding) is the fix -- verified
 * empirically by {@code ClaimsContractTest.listClaimsReturns200WithStatusFilterMatchingOnlyTheFilteredClaim},
 * which 500'd before this change on the simplest possible status-only filter and passes after it. */
public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    Optional<Claim> findByClaimIdAndTenantId(UUID claimId, UUID tenantId);
    Page<Claim> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Claim> findByTenantIdAndStatus(UUID tenantId, ClaimStatus status, Pageable pageable);
    Page<Claim> findByTenantIdAndClaimantPartyId(UUID tenantId, UUID claimantPartyId, Pageable pageable);
    Page<Claim> findByTenantIdAndClaimantPartyIdAndStatus(UUID tenantId, UUID claimantPartyId, ClaimStatus status, Pageable pageable);
    List<Claim> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);

    /**
     * The agents-realm-scoping counterpart to the derived-query combinations above --
     * {@code policyNumbers} is a THIRD optional dimension (claimantPartyId x status x agent
     * scope), following {@code PolicyRepository.search}'s exact null-safe-predicate shape rather
     * than a fourth derived-method combination. Claims carry no {@code agentOfRecordId} of their
     * own (they reference a policy, not an agent, directly) -- {@code policyNumbers} is resolved
     * by the caller via {@code PolicyApi.policyNumbersForAgentTeam}, joining through policy rather
     * than claims taking a dependency on {@code distribution} itself.
     */
    @Query("SELECT c FROM Claim c WHERE c.tenantId = :tenantId "
        + "AND (:claimantPartyId IS NULL OR c.claimantPartyId = :claimantPartyId) "
        + "AND (:status IS NULL OR c.status = :status) "
        + "AND (:policyNumbers IS NULL OR c.policyNumber IN :policyNumbers) "
        + "AND (:q IS NULL OR LOWER(c.policyNumber) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))")
    Page<Claim> search(@Param("tenantId") UUID tenantId,
                        @Param("claimantPartyId") UUID claimantPartyId,
                        @Param("status") ClaimStatus status,
                        @Param("policyNumbers") Collection<String> policyNumbers,
                        @Param("q") String q,
                        Pageable pageable);

    /** Backs claims/V3's partial unique index on (tenant_id, registration_idempotency_key) --
     * ClaimsApiImpl.registerClaim re-queries this on a caught unique-constraint violation to
     * return the EXISTING claim instead of erroring on a repeated registration attempt. */
    Optional<Claim> findByTenantIdAndRegistrationIdempotencyKey(UUID tenantId, String registrationIdempotencyKey);
}
