package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.underwriting.domain.CaseAccountCharge;

import java.util.List;
import java.util.UUID;

public interface CaseAccountChargeRepository extends JpaRepository<CaseAccountCharge, CaseAccountCharge.Key> {

    @Query("select c from CaseAccountCharge c where c.key.caseId = :caseId and c.tenantId = :tenantId")
    List<CaseAccountCharge> forCase(@Param("tenantId") UUID tenantId, @Param("caseId") UUID caseId);

    @Modifying
    @Query("delete from CaseAccountCharge c where c.key.caseId = :caseId and c.tenantId = :tenantId")
    void clear(@Param("tenantId") UUID tenantId, @Param("caseId") UUID caseId);
}
