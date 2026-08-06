package tz.co.nlolo.lifeplatform.audit.infrastructure;

import tz.co.nlolo.lifeplatform.audit.domain.FailedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FailedEventRepository extends JpaRepository<FailedEvent, UUID> {}
