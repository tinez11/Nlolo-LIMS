package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutInstalment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PayoutInstalmentRepository extends JpaRepository<PayoutInstalment, UUID> {

    List<PayoutInstalment> findByPolicyNumberOrderByDueDateAscRowOrderAsc(String policyNumber);

    List<PayoutInstalment> findByPolicyNumberAndStatus(String policyNumber, String status);

    List<PayoutInstalment> findByPolicyNumberAndDueDateAfter(String policyNumber, LocalDate after);

    /** Inclusive of {@code from}: a policy that ends today also withdraws today's instalment. */
    List<PayoutInstalment> findByPolicyNumberAndDueDateGreaterThanEqual(String policyNumber, LocalDate from);

    List<PayoutInstalment> findByStreamIdAndStatus(UUID streamId, String status);

    List<PayoutInstalment> findByStreamIdOrderByDueDateAsc(UUID streamId);

    List<PayoutInstalment> findByPaymentRunIdOrderByDueDateAsc(UUID paymentRunId);

    /**
     * What today's run may carry: a DUE instalment of an ACTIVE stream that is not already in one.
     *
     * <p>Every clause earns its place. ACTIVE excludes a stream whose proof of life has lapsed,
     * which is the whole control that justifies batch approval. The null run excludes an instalment
     * yesterday's run already took, so a run prepared twice cannot pay it twice.
     */
    @Query("select i from PayoutInstalment i, PayoutStream s where s.streamId = i.streamId "
        + "and i.tenantId = :tenantId and i.status = 'DUE' and s.status = 'ACTIVE' and i.paymentRunId is null")
    List<PayoutInstalment> findStreamInstalmentsReadyForARun(@Param("tenantId") UUID tenantId);

    boolean existsByPolicyNumberAndKindIn(String policyNumber, Collection<String> kinds);

    Page<PayoutInstalment> findByStatusIn(Collection<String> statuses, Pageable pageable);

    /**
     * Across every tenant, ids only -- the drain sets the tenant per row and reads the rest under
     * ordinary RLS. See the function's own comment for why a sweep must see past RLS at all.
     */
    @Query(value = "SELECT instalment_id, tenant_id FROM benefitpayout.instalments_falling_due()", nativeQuery = true)
    List<Object[]> findFallingDueAcrossTenants();
}
