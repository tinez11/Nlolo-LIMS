package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnitLinkedChoiceSplitEntity;

import java.util.List;
import java.util.UUID;

public interface UnitLinkedChoiceSplitRepository extends JpaRepository<UnitLinkedChoiceSplitEntity, UUID> {

    List<UnitLinkedChoiceSplitEntity> findByCaseIdOrderByFundCode(UUID caseId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UnitLinkedChoiceSplitEntity s WHERE s.caseId = :caseId")
    void deleteByCaseId(@Param("caseId") UUID caseId);
}
