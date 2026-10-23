package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
     *
     * <p>Four more dimensions since 2026-10-15, for the dashboards: the claim type, when it was
     * registered, and when it settled. The Instant bounds are CAST so Postgres can type a null
     * bind -- unlike the enum and UUID parameters beside them, a null Instant reaches the driver
     * untyped and the statement fails to prepare. A settled-date bound also excludes the
     * unsettled: a null settledAt is in no range, whatever the bounds.
     */
    @Query("SELECT c FROM Claim c WHERE c.tenantId = :tenantId "
        + "AND (:claimantPartyId IS NULL OR c.claimantPartyId = :claimantPartyId) "
        + "AND (:status IS NULL OR c.status = :status) "
        + "AND (:claimType IS NULL OR c.claimType = :claimType) "
        + "AND (:policyNumbers IS NULL OR c.policyNumber IN :policyNumbers) "
        + "AND (:q IS NULL OR LOWER(c.policyNumber) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))) "
        + "AND (CAST(:registeredFrom AS Instant) IS NULL OR c.createdAt >= :registeredFrom) "
        + "AND (CAST(:registeredTo AS Instant) IS NULL OR c.createdAt < :registeredTo) "
        + "AND (CAST(:settledFrom AS Instant) IS NULL "
        + "     OR (c.settledAt IS NOT NULL AND c.settledAt >= :settledFrom)) "
        + "AND (CAST(:settledTo AS Instant) IS NULL "
        + "     OR (c.settledAt IS NOT NULL AND c.settledAt < :settledTo))")
    Page<Claim> search(@Param("tenantId") UUID tenantId,
                        @Param("claimantPartyId") UUID claimantPartyId,
                        @Param("status") ClaimStatus status,
                        @Param("claimType") ClaimType claimType,
                        @Param("policyNumbers") Collection<String> policyNumbers,
                        @Param("q") String q,
                        @Param("registeredFrom") Instant registeredFrom,
                        @Param("registeredTo") Instant registeredTo,
                        @Param("settledFrom") Instant settledFrom,
                        @Param("settledTo") Instant settledTo,
                        Pageable pageable);

    /**
     * Settled claims per month and currency, with what they approved (2026-10-15). Native because
     * {@code to_char} with a time zone is Postgres's; JPQL has no equivalent.
     *
     * <p>Months are civil months in Africa/Dar_es_Salaam. A row with no currency -- an annuity
     * settled at zero -- is excluded: a currency-less amount cannot join a currency's total.
     */
    @Query(value = """
        SELECT to_char(c.settled_at AT TIME ZONE 'Africa/Dar_es_Salaam', 'YYYY-MM') AS month,
               c.approved_currency AS currency,
               COUNT(*) AS claims,
               SUM(c.approved_amount) AS total_approved
          FROM claims.claim c
         WHERE c.tenant_id = :tenantId
           AND c.settled_at IS NOT NULL
           AND c.approved_currency IS NOT NULL
           AND c.settled_at >= :from AND c.settled_at < :to
         GROUP BY month, c.approved_currency
         ORDER BY month, c.approved_currency
        """, nativeQuery = true)
    List<Object[]> settlementsByMonth(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                                       @Param("to") Instant to);

    /** Settled claims over a span, per type and currency (2026-10-23). Currency-less rows excluded, as above. */
    @Query(value = """
        SELECT c.claim_type, c.approved_currency, COUNT(*) AS claims, SUM(c.approved_amount) AS total_approved
          FROM claims.claim c
         WHERE c.tenant_id = :tenantId
           AND c.settled_at IS NOT NULL
           AND c.approved_currency IS NOT NULL
           AND c.settled_at >= :from AND c.settled_at < :to
         GROUP BY c.claim_type, c.approved_currency
         ORDER BY total_approved DESC, c.claim_type
        """, nativeQuery = true)
    List<Object[]> settlementsByType(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                                      @Param("to") Instant to);

    /** Settled claims per civil month with their summed days from registration, unrounded (2026-10-23). */
    @Query(value = """
        SELECT to_char(c.settled_at AT TIME ZONE 'Africa/Dar_es_Salaam', 'YYYY-MM') AS month,
               COUNT(*) AS claims,
               SUM(EXTRACT(EPOCH FROM (c.settled_at - c.created_at)) / 86400.0)::numeric AS total_days
          FROM claims.claim c
         WHERE c.tenant_id = :tenantId
           AND c.settled_at IS NOT NULL
           AND c.settled_at >= :from AND c.settled_at < :to
         GROUP BY month
         ORDER BY month
        """, nativeQuery = true)
    List<Object[]> settlementTurnaroundByMonth(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                                                @Param("to") Instant to);

    /** Claims registered per civil month (2026-10-23). */
    @Query(value = """
        SELECT to_char(c.created_at AT TIME ZONE 'Africa/Dar_es_Salaam', 'YYYY-MM') AS month, COUNT(*) AS claims
          FROM claims.claim c
         WHERE c.tenant_id = :tenantId
           AND c.created_at >= :from AND c.created_at < :to
         GROUP BY month
         ORDER BY month
        """, nativeQuery = true)
    List<Object[]> registrationsByMonth(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                                         @Param("to") Instant to);

    /** Backs claims/V3's partial unique index on (tenant_id, registration_idempotency_key) --
     * ClaimsApiImpl.registerClaim re-queries this on a caught unique-constraint violation to
     * return the EXISTING claim instead of erroring on a repeated registration attempt. */
    Optional<Claim> findByTenantIdAndRegistrationIdempotencyKey(UUID tenantId, String registrationIdempotencyKey);

    /** Claims of one type on one member of a scheme that are not in the given status -- in
     *  practice, the death claims on one life that were not rejected. Oldest first. */
    List<Claim> findByTenantIdAndPolicyNumberAndPolicyMemberIdAndClaimTypeAndStatusNotOrderByCreatedAtAsc(
        UUID tenantId, String policyNumber, UUID policyMemberId, ClaimType claimType, ClaimStatus status);

    /** The same, on individual business, where the policy names the life and no member is set. */
    List<Claim> findByTenantIdAndPolicyNumberAndPolicyMemberIdIsNullAndClaimTypeAndStatusNotOrderByCreatedAtAsc(
        UUID tenantId, String policyNumber, ClaimType claimType, ClaimStatus status);
}
