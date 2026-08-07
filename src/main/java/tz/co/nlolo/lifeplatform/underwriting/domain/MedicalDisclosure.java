package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Not exercised by this milestone's REST surface (openapi-underwriting.yaml's
 * OpenCaseRequest.medicalDisclosure is accepted but its structured Q&A schema is
 * explicitly "product-specific, see Deliverable 6" -- undefined at this layer).
 * Entity/repository built now so the table exists and a future milestone can populate
 * it without a schema migration; not wired into UnderwritingApiImpl's decision flow.
 */
@Entity
@Table(name = "medical_disclosure", schema = "underwriting")
public class MedicalDisclosure {

    @Id
    @UuidGenerator
    @Column(name = "medical_disclosure_id")
    private UUID medicalDisclosureId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "question_response_set", nullable = false, columnDefinition = "jsonb")
    private String questionResponseSet;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MedicalDisclosure() {}

    public MedicalDisclosure(UUID tenantId, UUID caseId, String questionResponseSet) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.questionResponseSet = questionResponseSet;
    }

    public UUID getCaseId() { return caseId; }
}
