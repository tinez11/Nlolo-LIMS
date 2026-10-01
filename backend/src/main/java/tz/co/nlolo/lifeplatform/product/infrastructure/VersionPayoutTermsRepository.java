package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.VersionPayoutTerms;

import java.util.UUID;

public interface VersionPayoutTermsRepository extends JpaRepository<VersionPayoutTerms, UUID> {}
