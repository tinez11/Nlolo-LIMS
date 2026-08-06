package tz.co.nlolo.lifeplatform.refdata.infrastructure;

import tz.co.nlolo.lifeplatform.refdata.domain.ReferenceCodeSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReferenceCodeSetRepository extends JpaRepository<ReferenceCodeSet, UUID> {
    List<ReferenceCodeSet> findByCodeSetKeyOrderByCode(String codeSetKey);
    List<ReferenceCodeSet> findByCodeSetKeyAndJurisdictionOrderByCode(String codeSetKey, String jurisdiction);
}
