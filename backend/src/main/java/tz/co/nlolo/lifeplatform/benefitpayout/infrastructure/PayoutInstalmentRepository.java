package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutInstalment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PayoutInstalmentRepository extends JpaRepository<PayoutInstalment, UUID> {

    List<PayoutInstalment> findByPolicyNumberOrderByDueDateAscRowOrderAsc(String policyNumber);

    List<PayoutInstalment> findByPolicyNumberAndStatus(String policyNumber, String status);

    List<PayoutInstalment> findByPolicyNumberAndDueDateAfter(String policyNumber, LocalDate after);

    boolean existsByPolicyNumberAndKindIn(String policyNumber, Collection<String> kinds);

    /**
     * Across every tenant, ids only -- the drain sets the tenant per row and reads the rest under
     * ordinary RLS. See the function's own comment for why a sweep must see past RLS at all.
     */
    @Query(value = "SELECT instalment_id, tenant_id FROM benefitpayout.instalments_falling_due()", nativeQuery = true)
    List<Object[]> findFallingDueAcrossTenants();
}
