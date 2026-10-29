package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UnderwritingCaseRepository extends JpaRepository<UnderwritingCase, UUID> {
    Optional<UnderwritingCase> findByCaseIdAndTenantId(UUID caseId, UUID tenantId);

    /**
     * One agent's clients' proposals waiting on them -- the "Needs your action" list (2026-10-20): returned, postponed,
     * or declined since {@code declinedSince}. Filtered here, not in Java: an agent's book holds every case ever
     * decided, and reading them all to keep a handful made the list too slow to load.
     */
    @Query("""
        SELECT c FROM UnderwritingCase c
         WHERE c.tenantId = :tenantId AND c.applicantPartyId IN :applicantPartyIds
           AND (c.status = 'RETURNED' OR (c.status = 'DECIDED' AND c.decisionOutcome = 'POSTPONED'))""")
    List<UnderwritingCase> findNeedingAgentAction(@Param("tenantId") UUID tenantId,
                                                  @Param("applicantPartyIds") java.util.Collection<UUID> applicantPartyIds);

    /**
     * The newest declines since {@code declinedSince}, for reading only -- a page of them, never the lot: a busy agent
     * can have hundreds, and a list of hundreds of finished decisions buries the few that need doing.
     */
    @Query("""
        SELECT c FROM UnderwritingCase c
         WHERE c.tenantId = :tenantId AND c.applicantPartyId IN :applicantPartyIds
           AND c.status = 'DECIDED' AND c.decisionOutcome = 'DECLINED' AND c.decisionDecidedAt >= :declinedSince
         ORDER BY c.decisionDecidedAt DESC""")
    List<UnderwritingCase> findRecentDeclines(@Param("tenantId") UUID tenantId,
                                              @Param("applicantPartyIds") java.util.Collection<UUID> applicantPartyIds,
                                              @Param("declinedSince") Instant declinedSince, Pageable pageable);
    Page<UnderwritingCase> findByTenantId(UUID tenantId, Pageable pageable);
    Page<UnderwritingCase> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);

    /**
     * Three optional dimensions -- status x applicantPartyId x agent scope -- in one null-safe
     * query, following {@code ClaimRepository.search}'s exact shape rather than enumerating derived
     * methods for every combination.
     *
     * <p>{@code applicantPartyIds} is the agent scope. Underwriting cases carry no agent reference
     * of their own, so the caller resolves the set via {@code PartyApi.partyIdsRegisteredBy} and
     * passes it in -- the same "join through the module that owns the fact" idiom claims uses with
     * {@code PolicyApi.policyNumbersForAgentTeam}, rather than underwriting taking a dependency on
     * distribution.
     *
     * <p>NULL means "no agent filter" (staff). An EMPTY set means "filter to nothing" and must
     * return zero rows -- an agent who has registered no one sees no cases, which is not the same
     * as seeing everything. Passing null for a caller that should have been scoped is the failure
     * mode this distinction exists to prevent.
     *
     * <p>Three more dimensions since 2026-10-15, for the dashboards: what was decided, whether the
     * case went to a senior, and when it was decided. Each is one more null-safe clause, and a
     * decided-date bound also excludes the undecided -- a null {@code decisionDecidedAt} is in no
     * range, whatever the bounds.
     */
    @Query("SELECT c FROM UnderwritingCase c WHERE c.tenantId = :tenantId "
        + "AND (:status IS NULL OR c.status = :status) "
        + "AND (:outcome IS NULL OR c.decisionOutcome = :outcome) "
        + "AND (:referral IS NULL OR c.referralStatus = :referral) "
        // The two instants are CAST so Postgres can type a null bind: unlike the String and UUID
        // parameters above, a null Instant reaches the driver untyped and the statement fails to
        // prepare with "could not determine data type of parameter".
        + "AND (CAST(:decidedFrom AS Instant) IS NULL "
        + "     OR (c.decisionDecidedAt IS NOT NULL AND c.decisionDecidedAt >= :decidedFrom)) "
        + "AND (CAST(:decidedTo AS Instant) IS NULL "
        + "     OR (c.decisionDecidedAt IS NOT NULL AND c.decisionDecidedAt < :decidedTo)) "
        + "AND (:applicantPartyId IS NULL OR c.applicantPartyId = :applicantPartyId) "
        + "AND (:applicantPartyIds IS NULL OR c.applicantPartyId IN :applicantPartyIds) "
        // Where it came from (2026-10-29), as CaseSource.of reads it: the portal with or without an amount, an agent's
        // quotation or agent of record, or the office.
        + "AND (:source IS NULL "
        + "  OR (:source = 'CUSTOMER_ONLINE' AND c.sourceOfBusiness = 'CUSTOMER_PORTAL' AND c.sumAssuredAmount > 0) "
        + "  OR (:source = 'CALL_BACK' AND c.sourceOfBusiness = 'CUSTOMER_PORTAL' "
        + "      AND (c.sumAssuredAmount IS NULL OR c.sumAssuredAmount = 0)) "
        + "  OR (:source = 'AGENT' AND (c.sourceOfBusiness IS NULL OR c.sourceOfBusiness <> 'CUSTOMER_PORTAL') "
        + "      AND (c.agentOfRecordId IS NOT NULL OR c.sourceOfBusiness = 'Agent quotation')) "
        + "  OR (:source = 'STAFF' AND (c.sourceOfBusiness IS NULL "
        + "      OR (c.sourceOfBusiness <> 'CUSTOMER_PORTAL' AND c.sourceOfBusiness <> 'Agent quotation')) "
        + "      AND c.agentOfRecordId IS NULL))")
    Page<UnderwritingCase> search(@Param("tenantId") UUID tenantId,
                                   @Param("status") String status,
                                   @Param("outcome") String outcome,
                                   @Param("referral") String referral,
                                   @Param("decidedFrom") Instant decidedFrom,
                                   @Param("decidedTo") Instant decidedTo,
                                   @Param("applicantPartyId") UUID applicantPartyId,
                                   @Param("applicantPartyIds") Set<UUID> applicantPartyIds,
                                   @Param("source") String source,
                                   Pageable pageable);

    /**
     * Decided cases no policy has been issued from (2026-10-08): what manual issuance may still pick.
     * Issuing a policy locks the case's sale, so an unlocked decided case has none; a member's evidence
     * case never issues one. Declined cases stay in: an underwriting override issues against them.
     */
    String AWAITING_ISSUE = "SELECT c FROM UnderwritingCase c WHERE c.tenantId = :tenantId AND c.status = 'DECIDED' "
        + "AND c.saleLockedAt IS NULL AND c.evidenceForPolicyNumber IS NULL";

    @Query(AWAITING_ISSUE)
    Page<UnderwritingCase> awaitingIssue(@Param("tenantId") UUID tenantId, Pageable pageable);

    /** As {@link #awaitingIssue}, narrowed to proposal numbers containing {@code pattern} (lower case, with wildcards). */
    @Query(AWAITING_ISSUE + " AND LOWER(c.proposalNumber) LIKE :pattern")
    Page<UnderwritingCase> awaitingIssueMatching(@Param("tenantId") UUID tenantId, @Param("pattern") String pattern,
                                                 Pageable pageable);

    /**
     * Decisions per month and outcome, with the mean days from opening to decision (2026-10-15). Native because the
     * interval arithmetic and {@code to_char} are Postgres's; JPQL has neither.
     *
     * <p>Months are civil months in Africa/Dar_es_Salaam: a case decided at 23:30 on 31 October UTC was decided on
     * 1 November here, and counting it in October would put it in a month the business had already closed.
     */
    @Query(value = """
        SELECT to_char(c.decision_decided_at AT TIME ZONE 'Africa/Dar_es_Salaam', 'YYYY-MM') AS month,
               c.decision_outcome AS outcome,
               COUNT(*) AS cases,
               ROUND(AVG(EXTRACT(EPOCH FROM (c.decision_decided_at - c.created_at)) / 86400.0)::numeric, 1) AS avg_days
          FROM underwriting.underwriting_case c
         WHERE c.tenant_id = :tenantId
           AND c.decision_decided_at IS NOT NULL
           AND c.decision_decided_at >= :from AND c.decision_decided_at < :to
         GROUP BY month, c.decision_outcome
         ORDER BY month, c.decision_outcome
        """, nativeQuery = true)
    List<Object[]> decisionsByMonth(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                                     @Param("to") Instant to);

    /** Decisions per civil month, every outcome, with the summed days unrounded (2026-10-23). */
    @Query(value = """
        SELECT to_char(c.decision_decided_at AT TIME ZONE 'Africa/Dar_es_Salaam', 'YYYY-MM') AS month,
               COUNT(*) AS cases,
               SUM(EXTRACT(EPOCH FROM (c.decision_decided_at - c.created_at)) / 86400.0)::numeric AS total_days
          FROM underwriting.underwriting_case c
         WHERE c.tenant_id = :tenantId
           AND c.decision_decided_at IS NOT NULL
           AND c.decision_decided_at >= :from AND c.decision_decided_at < :to
         GROUP BY month
         ORDER BY month
        """, nativeQuery = true)
    List<Object[]> decisionTurnaroundByMonth(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                                              @Param("to") Instant to);
}
