package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.BaseRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BaseRateRepository extends JpaRepository<BaseRate, UUID> {

    List<BaseRate> findByProductVersionId(UUID productVersionId);

    /**
     * The pricing lookup: the cell whose age range contains {@code age}, for that
     * sex and smoker status. `ageTo` is inclusive.
     *
     * Returns an Optional deliberately: on the pricing path an unmatched cell is a
     * 422 naming the dimension, NOT a neutral fallback.
     * {@code ProductApi.resolveRatingMultiplier} returns {@code BigDecimal.ONE}
     * when no band matches -- correct for underwriting risk scoring, its only
     * caller, but a silent mispricing on a premium, since a mistyped band would
     * quietly price at the base rate and nothing would fail. Pricing does not reuse
     * that method, and this signature cannot express the same fallback.
     *
     * Overlapping bands would make the result depend on row order; publish-time
     * validation rejects them, so at most one row can match.
     */
    @Query("""
        select b from BaseRate b
        where b.productVersionId = :productVersionId
          and b.sex = :sex
          and b.smokerStatus = :smokerStatus
          and :age between b.ageFrom and b.ageTo
        """)
    Optional<BaseRate> findApplicable(@Param("productVersionId") UUID productVersionId,
                                      @Param("age") int age,
                                      @Param("sex") String sex,
                                      @Param("smokerStatus") String smokerStatus);

    boolean existsByProductVersionId(UUID productVersionId);
}
