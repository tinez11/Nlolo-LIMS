package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.underwriting.domain.FuneralApplicationLifeEntity;

import java.util.List;
import java.util.UUID;

public interface FuneralApplicationLifeRepository extends JpaRepository<FuneralApplicationLifeEntity, UUID> {
    List<FuneralApplicationLifeEntity> findByCaseIdOrderByPosition(UUID caseId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM FuneralApplicationLifeEntity l WHERE l.caseId = :caseId")
    void deleteByCaseId(UUID caseId);
}
