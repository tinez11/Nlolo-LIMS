package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.bonus.domain.Participant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParticipantRepository extends JpaRepository<Participant, String> {

    boolean existsByProductId(UUID productId);

    /** The head, locked for the entry about to be written -- accumulation's lockForPosting. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Participant p where p.policyNumber = :policyNumber")
    Optional<Participant> lockForPosting(@Param("policyNumber") String policyNumber);

    /** The drain's next batch: participants on the product with no outcome for this declaration yet. */
    @Query(value = "SELECT p.policy_number FROM bonus.participant p WHERE p.product_id = :productId "
        + "AND NOT EXISTS (SELECT 1 FROM bonus.declaration_outcome o WHERE o.declaration_id = :declarationId "
        + "AND o.policy_number = p.policy_number) ORDER BY p.policy_number LIMIT 200", nativeQuery = true)
    List<String> awaitingOutcome(@Param("productId") UUID productId, @Param("declarationId") UUID declarationId);
}
