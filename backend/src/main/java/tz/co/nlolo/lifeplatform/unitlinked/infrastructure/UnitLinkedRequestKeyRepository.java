package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.RequestKey;

public interface UnitLinkedRequestKeyRepository extends JpaRepository<RequestKey, RequestKey.Id> {}
