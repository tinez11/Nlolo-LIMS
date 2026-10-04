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

    /** Open annuity streams whose expansion stops short of the horizon (product step 5). Ids only. */
    @Query(value = "SELECT stream_id, tenant_id FROM benefitpayout.annuity_streams_due_for_rollforward(:horizon)",
        nativeQuery = true)
    List<Object[]> findAnnuityStreamsDueForRollForward(@org.springframework.data.repository.query.Param("horizon") java.time.LocalDate horizon);

    /** Row-locked, so two roll-forwards cannot both expand one stream. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PayoutStream s where s.streamId = :streamId")
    java.util.Optional<PayoutStream> lockById(@org.springframework.data.repository.query.Param("streamId") UUID streamId);

    /** A policy's annuity stream -- there is at most one, at row 0. */
    @Query("select s from PayoutStream s where s.policyNumber = :policyNumber and s.openEnded = true")
    java.util.Optional<PayoutStream> findAnnuityStream(@org.springframework.data.repository.query.Param("policyNumber") String policyNumber);
}
