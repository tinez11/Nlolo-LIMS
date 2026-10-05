package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.unitlinked.domain.NoticeLog;

import java.util.List;
import java.util.UUID;

public interface NoticeLogRepository extends JpaRepository<NoticeLog, UUID> {

    @Query("select count(n) > 0 from NoticeLog n where n.tenantId = :tenantId and n.policyNumber = :policyNumber"
        + " and n.kind = :kind and n.month = :month")
    boolean sent(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber, @Param("kind") String kind,
                 @Param("month") String month);

    /** [policy_number, tenant_id] of every unit-linked policy across tenants (unitlinked V2), for the sweeps. */
    @Query(value = "SELECT policy_number, tenant_id FROM unitlinked.unit_linked_policies()", nativeQuery = true)
    List<Object[]> unitLinkedPolicies();
}
