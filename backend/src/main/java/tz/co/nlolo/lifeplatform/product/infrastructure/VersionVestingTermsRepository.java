package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.VersionVestingTerms;

import java.util.UUID;

public interface VersionVestingTermsRepository extends JpaRepository<VersionVestingTerms, UUID> {}
