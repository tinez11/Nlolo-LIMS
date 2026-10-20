package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.underwriting.domain.AcceptedQuotation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AcceptedQuotationRepository extends JpaRepository<AcceptedQuotation, UUID> {

    Optional<AcceptedQuotation> findByQuotationIdAndTenantId(UUID quotationId, UUID tenantId);

    Optional<AcceptedQuotation> findByCaseIdAndTenantId(UUID caseId, UUID tenantId);

    /** [quotation_id, tenant_id] confirmed before {@code before} and never submitted, across tenants (V22). */
    @Query(value = "SELECT quotation_id, tenant_id FROM underwriting.unsubmitted_quotations_before(:before)",
        nativeQuery = true)
    List<Object[]> unsubmittedBefore(@Param("before") Instant before);
}
