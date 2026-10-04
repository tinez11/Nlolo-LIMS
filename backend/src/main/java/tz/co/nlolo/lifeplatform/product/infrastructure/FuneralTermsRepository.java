package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.FuneralTermsEntity;

import java.util.List;
import java.util.UUID;

public interface FuneralTermsRepository extends JpaRepository<FuneralTermsEntity, UUID> {

}
