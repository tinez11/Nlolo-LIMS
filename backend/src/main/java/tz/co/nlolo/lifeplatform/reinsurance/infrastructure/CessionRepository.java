package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CessionRepository extends JpaRepository<Cession, UUID> {
    /** Ordered by creation so a caller taking only the first result (recovery -- one treaty per
     * policy) gets a deterministic pick rather than one dependent on database row order. */
    List<Cession> findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(UUID tenantId, String policyNumber);

    /** Keyed on (tenant, policy) only, matching {@code ux_cession_once} (reinsurance/V2 section 10)
     * -- the design is one treaty per policy, so "a cession already exists for this policy" is the
     * real idempotency check, not "for this (policy, treaty) pair". */
    boolean existsByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);

    /**
     * What has actually been ceded to one treaty -- the read this module never had.
     *
     * <p>Cessions could only be listed BY POLICY, so a treaty could state a retention limit and
     * a cession percent while the platform held hundreds of cessions naming it and could not
     * answer "how much have we ceded to this reinsurer". Every cession already carries
     * {@code treaty_id}; only the query was missing.
     *
     * <p>PAGED, unlike the per-policy finder beside it. A policy has a handful of cessions; a
     * treaty accumulates one per policy it covers for as long as it runs -- one in this
     * platform's own dev data already has 61 -- so an unpaged read here has no upper bound.
     */
    Page<Cession> findByTenantIdAndTreatyIdOrderByCreatedAtDesc(UUID tenantId, UUID treatyId, Pageable pageable);

    /**
     * The treaty's totals, computed in the database rather than by summing a page.
     *
     * <p>Summing the rows a caller happens to be looking at would report one page's worth of
     * cession as the treaty's utilisation, which is a number that is wrong in exactly the way
     * nobody notices: it looks plausible and it is always too small.
     *
     * <p>Returns a single row of (count, ceded, ceded premium). Both sums are coalesced, because
     * a treaty with no cessions yet must report zero rather than null -- an ACTIVE treaty nobody
     * has ceded to is an ordinary state.
     *
     * <p>A PROJECTION INTERFACE, not {@code Object[]}, and that is not a style preference: a
     * single-row multi-column JPQL query declared as {@code Object[]} hands back a one-element
     * array CONTAINING the row, so reading {@code totals[0]} as the count throws
     * {@code ClassCastException: [Ljava.lang.Object; cannot be cast to java.lang.Number} at
     * runtime while compiling perfectly. Found by calling the endpoint. Named accessors make the
     * shape unambiguous and the trap unreachable.
     */
    @Query("""
        select count(c) as cessionCount,
               coalesce(sum(c.cededAmount), 0) as cededAmount,
               coalesce(sum(c.cededPremiumAmount), 0) as cededPremium
          from Cession c
         where c.tenantId = :tenantId and c.treatyId = :treatyId
        """)
    TreatyTotals totalsForTreaty(@Param("tenantId") UUID tenantId, @Param("treatyId") UUID treatyId);

    /** The shape {@link #totalsForTreaty} returns. */
    interface TreatyTotals {
        long getCessionCount();
        BigDecimal getCededAmount();
        BigDecimal getCededPremium();
    }
}
