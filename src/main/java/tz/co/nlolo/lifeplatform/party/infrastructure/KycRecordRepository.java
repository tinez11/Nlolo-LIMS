package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.domain.KycRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KycRecordRepository extends JpaRepository<KycRecord, UUID> {}
