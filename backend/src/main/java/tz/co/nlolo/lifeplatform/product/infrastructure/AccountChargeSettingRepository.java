package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.AccountChargeSetting;

import java.util.UUID;

public interface AccountChargeSettingRepository extends JpaRepository<AccountChargeSetting, UUID> {
}
