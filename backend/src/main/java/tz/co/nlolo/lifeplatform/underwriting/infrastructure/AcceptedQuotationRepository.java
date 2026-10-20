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

    /**
     * Marks it submitted only if nobody has yet -- the database decides, not a read made a moment earlier. Two
     * submissions racing (a double click, two tabs) both read "not submitted"; only one of these updates matches, and
     * the other caller's transaction, with the case it opened, rolls back. Returns the rows changed.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE AcceptedQuotation q SET q.caseId = :caseId, q.applicantPartyId = :applicantPartyId, q.submittedAt = :at
         WHERE q.quotationId = :quotationId AND q.tenantId = :tenantId AND q.caseId IS NULL""")
    int markSubmittedIfNot(@Param("quotationId") UUID quotationId, @Param("tenantId") UUID tenantId,
                           @Param("caseId") UUID caseId, @Param("applicantPartyId") UUID applicantPartyId,
                           @Param("at") Instant at);

    /** [quotation_id, tenant_id] confirmed before {@code before} and never submitted, across tenants (V22). */
    @Query(value = "SELECT quotation_id, tenant_id FROM underwriting.unsubmitted_quotations_before(:before)",
        nativeQuery = true)
    List<Object[]> unsubmittedBefore(@Param("before") Instant before);
}
