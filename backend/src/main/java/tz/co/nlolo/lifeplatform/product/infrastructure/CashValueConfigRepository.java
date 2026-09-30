package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.CashValueConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CashValueConfigRepository extends JpaRepository<CashValueConfig, UUID> {
}
