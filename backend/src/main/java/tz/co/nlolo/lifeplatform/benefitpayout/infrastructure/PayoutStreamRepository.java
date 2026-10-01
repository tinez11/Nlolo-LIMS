package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutStream;

import java.util.List;
import java.util.UUID;

public interface PayoutStreamRepository extends JpaRepository<PayoutStream, UUID> {

    List<PayoutStream> findByPolicyNumber(String policyNumber);

    /**
     * Across every tenant, ids only -- the drain sets the tenant per row and reads the rest under
     * ordinary RLS. See the function's own comment for why a sweep must see past RLS at all.
     */
    @Query(value = "SELECT stream_id, tenant_id FROM benefitpayout.streams_due_for_proof_of_life()",
        nativeQuery = true)
    List<Object[]> findDueForProofOfLifeAcrossTenants();
}
