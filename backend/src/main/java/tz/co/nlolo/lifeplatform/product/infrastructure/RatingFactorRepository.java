package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.RatingFactor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RatingFactorRepository extends JpaRepository<RatingFactor, UUID> {
    List<RatingFactor> findByProductVersionId(UUID productVersionId);
    List<RatingFactor> findByProductVersionIdAndFactorType(UUID productVersionId, String factorType);

    /**
     * The AGE row whose inclusive range covers {@code age}.
     *
     * <p>Returns a list, not an Optional, deliberately: publish-time validation rejects
     * overlapping AGE ranges, so the steady state is 0 or 1 rows — but the invariant is
     * enforced where the rows are authored, not assumed here. A version published before
     * that validation existed could still hold overlaps, and silently taking one of two
     * matches would be exactly the scan-order mispricing this platform has now found four
     * times. The caller looks at the size and says so.
     *
     * <p>Rows with null bounds (every AGE row published before V5) match nothing, which is
     * what keeps existing versions rating age the way they always have: not at all.
     */
    @Query("SELECT r FROM RatingFactor r WHERE r.productVersionId = :productVersionId "
        + "AND r.factorType = 'AGE' AND r.ageFrom <= :age AND r.ageTo >= :age "
        + "ORDER BY r.ageFrom, r.ratingTableId")
    List<RatingFactor> findAgeBandCovering(@Param("productVersionId") UUID productVersionId,
                                            @Param("age") int age);
}
