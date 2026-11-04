package tz.co.nlolo.lifeplatform.document.infrastructure;

import tz.co.nlolo.lifeplatform.document.domain.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, String> {

    /**
     * Every document filed under one owner context, newest first.
     *
     * <p>{@code owner_context} is the {@code "<aggregate>:<id>"} convention every uploader already
     * writes ({@code party:<uuid>} for KYC evidence, {@code claim:<uuid>} for claim evidence), and
     * {@code idx_document_record_owner} has indexed it since V1 — this direction was simply never
     * queried, so documents were reachable only by a ref someone already held.
     *
     * <p>The tie-breaker is not decoration: {@code uploaded_at} is assigned by {@code Instant.now()}
     * in Java, so two documents attached in the same request can share it exactly, and without
     * {@code documentRef} the order of those two would be whatever the scan returned.
     */
    List<DocumentRecord> findByTenantIdAndOwnerContextOrderByUploadedAtDescDocumentRefDesc(
        UUID tenantId, String ownerContext);

    /** The newest upload of one type per owner whose context starts with a prefix (2026-11-03). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT d.ownerContext, max(d.uploadedAt) FROM DocumentRecord d
         WHERE d.tenantId = :tenantId AND d.documentType = :type AND d.ownerContext LIKE :prefix
         GROUP BY d.ownerContext""")
    List<Object[]> latestUploads(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                                 @org.springframework.data.repository.query.Param("type")
                                 tz.co.nlolo.lifeplatform.document.api.DocumentType type,
                                 @org.springframework.data.repository.query.Param("prefix") String prefix);
}
