package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.VersionAnnuityTerms;

import java.util.UUID;

public interface VersionAnnuityTermsRepository extends JpaRepository<VersionAnnuityTerms, UUID> {}
