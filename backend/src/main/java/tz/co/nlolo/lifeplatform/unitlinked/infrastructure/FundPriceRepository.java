package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every finder takes the tenant: tests connect as the table owner, so RLS alone would not scope them. */
public interface FundPriceRepository extends JpaRepository<FundPrice, UUID> {

    Optional<FundPrice> findByTenantIdAndPriceId(UUID tenantId, UUID priceId);

    @Query("select p from FundPrice p where p.tenantId = :tenantId and p.fundId = :fundId"
        + " and p.status = 'APPROVED' and p.valuationDate = :date")
    Optional<FundPrice> findApproved(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId,
                                     @Param("date") LocalDate date);

    @Query("select p from FundPrice p where p.tenantId = :tenantId and p.fundId = :fundId"
        + " and p.status = 'PROPOSED' and p.valuationDate = :date")
    Optional<FundPrice> findProposed(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId,
                                     @Param("date") LocalDate date);

    /** The last approved price strictly before {@code before}: the baseline a new price moves from. */
    @Query("select p from FundPrice p where p.tenantId = :tenantId and p.fundId = :fundId"
        + " and p.status = 'APPROVED' and p.valuationDate < :before order by p.valuationDate desc")
    List<FundPrice> findApprovedBefore(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId,
                                       @Param("before") LocalDate before, Limit limit);

    default Optional<FundPrice> findLatestApprovedBefore(UUID tenantId, UUID fundId, LocalDate before) {
        return findApprovedBefore(tenantId, fundId, before, Limit.of(1)).stream().findFirst();
    }

    /** The latest approved price dated after {@code date}, if any: a later price already in force. */
    @Query("select p from FundPrice p where p.tenantId = :tenantId and p.fundId = :fundId"
        + " and p.status = 'APPROVED' and p.valuationDate > :date order by p.valuationDate desc")
    List<FundPrice> findApprovedAfter(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId,
                                      @Param("date") LocalDate date, Limit limit);

    /** Every approved price dated on or after {@code from}, oldest first (U2, plan D3): where a switch looks for a
     *  date on which all its funds are priced. */
    @Query("select p from FundPrice p where p.tenantId = :tenantId and p.fundId = :fundId"
        + " and p.status = 'APPROVED' and p.valuationDate >= :from order by p.valuationDate asc")
    List<FundPrice> findApprovedFrom(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId,
                                     @Param("from") LocalDate from);

    @Query("select p from FundPrice p where p.tenantId = :tenantId and p.fundId = :fundId"
        + " and p.valuationDate between :from and :to order by p.valuationDate desc, p.proposedAt desc")
    List<FundPrice> findBetween(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId,
                                @Param("from") LocalDate from, @Param("to") LocalDate to);
}
