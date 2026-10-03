package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.bonus.domain.Declaration;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeclarationRepository extends JpaRepository<Declaration, UUID> {
    List<Declaration> findByProductIdOrderByValuationDateDescProposedAtDesc(UUID productId);

    /** The latest APPROVED declaration on or before a date -- the one an exit is valued against. */
    @Query(value = "SELECT * FROM bonus.declaration WHERE product_id = :productId AND status = 'APPROVED' "
        + "AND valuation_date <= :date ORDER BY valuation_date DESC LIMIT 1", nativeQuery = true)
    Optional<Declaration> latestApprovedOnOrBefore(@Param("productId") UUID productId, @Param("date") LocalDate date);

    @Query(value = "SELECT declaration_id, tenant_id FROM bonus.declarations_due()", nativeQuery = true)
    List<Object[]> findDueAcrossTenants();
}
