package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.unitlinked.domain.SwitchRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SwitchRequestRepository extends JpaRepository<SwitchRequest, UUID> {

    Optional<SwitchRequest> findByTenantIdAndSwitchId(UUID tenantId, UUID switchId);

    Optional<SwitchRequest> findByTenantIdAndPolicyNumberAndStatus(UUID tenantId, String policyNumber, String status);

    boolean existsByTenantIdAndPolicyNumberAndStatus(UUID tenantId, String policyNumber, String status);

    List<SwitchRequest> findByTenantIdAndPolicyNumberOrderByRequestedAtDesc(UUID tenantId, String policyNumber);

    /** The waiting switches that move a fund just priced for a date on or after their binding. */
    @Query("select distinct s from SwitchRequest s join s.legs l where s.tenantId = :tenantId and s.status = 'WAITING'"
        + " and l.fundId = :fundId and s.boundDate <= :upTo")
    List<SwitchRequest> findWaitingInvolving(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId,
                                             @Param("upTo") LocalDate upTo);

    /** Switches already executed in a policy year: the free allowance is counted against these. */
    @Query("select count(s) from SwitchRequest s where s.tenantId = :tenantId and s.policyNumber = :policyNumber"
        + " and s.status = 'EXECUTED' and s.executedOn >= :from and s.executedOn <= :to")
    long countExecutedBetween(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber,
                              @Param("from") LocalDate from, @Param("to") LocalDate to);
}
