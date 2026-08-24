package tz.co.nlolo.lifeplatform.document.infrastructure;

import tz.co.nlolo.lifeplatform.document.domain.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, String> {}
