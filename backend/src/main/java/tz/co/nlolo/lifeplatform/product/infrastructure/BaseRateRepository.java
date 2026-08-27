package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.BaseRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BaseRateRepository extends JpaRepository<BaseRate, UUID> {

    List<BaseRate> findByProductVersionId(UUID productVersionId);

    /**
     * The pricing lookup. Returns an Optional deliberately: on the pricing path an
     * unmatched cell is a 422 naming the factor, NOT a neutral fallback.
     *
     * {@code ProductApi.resolveRatingMultiplier} returns {@code BigDecimal.ONE}
     * when no band matches -- correct for underwriting risk scoring, which is its
     * only caller, but a silent mispricing on a premium: a mistyped age band would
     * quietly price at the base rate and nothing would fail. Pricing does not
     * reuse that method, and this one cannot express the same fallback.
     */
    Optional<BaseRate> findByProductVersionIdAndAgeBandAndSexAndSmokerStatus(
            UUID productVersionId, String ageBand, String sex, String smokerStatus);

    boolean existsByProductVersionId(UUID productVersionId);
}
