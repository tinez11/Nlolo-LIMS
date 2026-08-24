package tz.co.nlolo.lifeplatform.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyc_record", schema = "party")
public class KycRecord {

    @Id
    @GeneratedValue
    @Column(name = "kyc_record_id")
    private UUID kycRecordId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Column(name = "evidence_document_ref", nullable = false)
    private String evidenceDocumentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private KycStatus status;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KycRecord() {}

    public KycRecord(UUID tenantId, UUID partyId, String evidenceDocumentRef, KycStatus status, String verifiedBy) {
        this.tenantId = tenantId;
        this.partyId = partyId;
        this.evidenceDocumentRef = evidenceDocumentRef;
        this.status = status;
        this.verifiedBy = verifiedBy;
        this.verifiedAt = Instant.now();
        this.createdAt = Instant.now();
    }
}
