package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.VersionAccumulationTerms;

import java.util.UUID;

public interface VersionAccumulationTermsRepository extends JpaRepository<VersionAccumulationTerms, UUID> {}
